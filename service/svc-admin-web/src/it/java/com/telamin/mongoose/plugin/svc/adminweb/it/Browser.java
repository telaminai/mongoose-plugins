/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb.it;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Headless Chromium over the bundled UI, with DevTools-level checks: a console error, an uncaught page error,
 * a failed request or a 5xx response fails the test, as does any 4xx outside {@link #EXPECTED_4XX}. Every test
 * keeps a full-page screenshot per step; a failing test also keeps a Playwright trace (open it with
 * {@code mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="show-trace <zip>"}).
 */
final class Browser implements AutoCloseable {

    /**
     * 4xx responses the UI provokes on purpose while probing optional features; anything else is a failure.
     * /api/files 404s when no loader directory is configured (observed on every page load). A 401 from
     * /api/session/login is the auth probe and is also allowed.
     */
    static final Set<String> EXPECTED_4XX = Set.of("/api/files");

    private static Playwright playwright;
    private static com.microsoft.playwright.Browser chromium;

    final Page page;
    private final BrowserContext context;
    private final Path dir;
    private final String base;
    private int step;
    final List<String> problems = new CopyOnWriteArrayList<>();
    /** Every HTTP error the page saw, allowed or not, for the failure message. */
    final List<String> httpErrors = new CopyOnWriteArrayList<>();

    static synchronized com.microsoft.playwright.Browser chromium() {
        if (chromium == null) {
            playwright = Playwright.create();
            chromium = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        }
        return chromium;
    }

    static synchronized void shutdown() {
        if (chromium != null) chromium.close();
        if (playwright != null) playwright.close();
        chromium = null;
        playwright = null;
    }

    Browser(String testName, String base) throws Exception {
        this.base = base;
        this.dir = Files.createDirectories(Path.of(System.getProperty("it.artifacts.dir", "target/admin-web-it"), "ui", testName));
        try (var old = Files.list(dir)) {
            for (Path f : old.toList()) Files.deleteIfExists(f); // a previous run's screenshots and trace
        }
        context = chromium().newContext(new com.microsoft.playwright.Browser.NewContextOptions().setViewportSize(1400, 1000));
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(false));
        page = context.newPage();
        page.setDefaultTimeout(10_000);
        page.onConsoleMessage(m -> {
            if (!"error".equals(m.type())) return;
            // Chromium echoes every HTTP error status as a console error; those are judged by status below,
            // so an allowed probe 404 is not counted twice
            if (m.text().startsWith("Failed to load resource: the server responded with a status of")) return;
            problems.add("console error: " + m.text() + " @ " + m.location());
        });
        page.onPageError(e -> problems.add("page error: " + e));
        page.onRequestFailed(r -> {
            // a WebSocket or poll aborted by navigation/close is not a server failure
            String failure = String.valueOf(r.failure());
            if (!failure.contains("ERR_ABORTED")) problems.add("request failed: " + r.method() + " " + r.url() + " " + failure);
        });
        page.onResponse(r -> {
            int status = r.status();
            String path = java.net.URI.create(r.url()).getPath();
            if (status >= 400) httpErrors.add(status + " " + r.request().method() + " " + path);
            if (status >= 500) problems.add("HTTP " + status + " " + r.request().method() + " " + path);
            else if (status >= 400 && !EXPECTED_4XX.contains(path) && !(path.equals("/api/session/login") && status == 401)) {
                problems.add("HTTP " + status + " " + r.request().method() + " " + path);
            }
        });
    }

    void open() {
        page.navigate(base + "/");
        assertThat(page.locator("[data-testid=nav-overview]")).isVisible();
        // the overview fetches services/agents/audit/pipes in parallel; wait until those requests settle
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
    }

    Locator byTestId(String id) {
        return page.locator("[data-testid=" + id + "]");
    }

    Locator processorRow(String selector) {
        return page.locator("[data-testid=overview-processor-row][data-processor=\"" + selector + "\"]");
    }

    /** Run an admin command through the Commands view, as an operator would; returns the visible result text. */
    String command(String name, String... args) {
        byTestId("nav-commands").click();
        byTestId("cmd-filter").fill(name);
        page.locator("[data-testid=cmd-item][data-command=\"" + name + "\"]").click();
        byTestId("cmd-args").fill(String.join("\n", args));
        byTestId("cmd-run").click();
        Locator result = page.locator("[data-testid=cmd-output]:visible, [data-testid=cmd-error]:visible").first();
        assertThat(result).isVisible();
        page.waitForFunction("() => !document.querySelector('[data-testid=cmd-run]').disabled");
        List<String> texts = new ArrayList<>();
        for (Locator l : List.of(byTestId("cmd-output"), byTestId("cmd-error"))) {
            if (l.isVisible()) texts.add(l.innerText());
        }
        return String.join("\n", texts);
    }

    void shot(String label) {
        page.screenshot(new Page.ScreenshotOptions().setFullPage(true)
                .setPath(dir.resolve(String.format("%02d-%s.png", ++step, label.replaceAll("[^A-Za-z0-9._-]", "_")))));
    }

    /** Fails if the browser saw a console error, page error, failed request or unexpected HTTP error. */
    void assertClean() {
        if (!problems.isEmpty()) throw new AssertionError("browser problems: " + problems + "; all HTTP errors seen: " + httpErrors);
    }

    void keepTrace() {
        context.tracing().stop(new Tracing.StopOptions().setPath(dir.resolve("trace.zip")));
    }

    @Override
    public void close() {
        try {
            Files.write(dir.resolve("http-errors.txt"), httpErrors);
        } catch (java.io.IOException ignored) {
            // artifacts are best effort
        }
        try {
            context.tracing().stop();
        } catch (RuntimeException alreadyStopped) {
            // keepTrace() stopped it
        }
        context.close();
    }
}
