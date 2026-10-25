/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * UI regression pack: the failure scenarios a-h through the bundled web UI in headless Chromium, asserting
 * what an operator sees (stable data-testid selectors), that the browser logged no console/page errors and
 * that no request failed. Screenshots per step are kept under target/admin-web-it/ui/<test>/ as artifacts;
 * they are evidence, not the pass criterion (no pixel diffs).
 *
 * <p>Assertions pin CURRENT behaviour, including what the proposal classifies as missing or misleading
 * (comments say which). The desired UI is in {@link AdminWebUiProposedIT} (tag "proposed").
 */
@Tag("ui-it")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AdminWebUiScenarioIT {

    @AfterAll
    static void closeBrowser() {
        Browser.shutdown();
    }

    interface Body {
        void run(Browser b) throws Exception;
    }

    static void ui(TestInfo info, ScenarioServer s, Body body) throws Exception {
        String name = info.getTestMethod().orElseThrow().getName();
        try (Browser b = new Browser(name, s.webBase())) {
            try {
                body.run(b);
                b.assertClean();
            } catch (Throwable t) {
                b.shot("failure");
                b.keepTrace();
                throw t;
            }
        }
    }

    @Test
    void a_quarantine(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.quarantine(s);
            ui(info, s, b -> {
                b.open();
                assertThat(b.processorRow("pricing/peer")).isVisible();
                assertThat(b.processorRow("hand/handwritten")).isVisible();
                // CURRENT (misleading): the quarantined processor is simply absent from the overview
                assertThat(b.processorRow("pricing/pricer")).hasCount(0);
                b.shot("overview-pricer-vanished");
                String status = b.command("control.group.status", "pricing");
                assertTrue(status.contains("quarantinedProcessors={pricer="), status);
                b.shot("commands-group-status");
                String stop = b.command("server.processors.stop", "pricing/pricer");
                assertTrue(stop.contains("stop FAILED: processor quarantined"), stop);
                b.shot("commands-stop-refused");
            });
        }
    }

    @Test
    void b_deadGroup(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.deadGroup(s);
            ui(info, s, b -> {
                b.open();
                // CURRENT (misleading): the dead group's processor looks like any other
                assertThat(b.processorRow("doomed/ok")).isVisible();
                b.byTestId("nav-managed").click();
                var card = b.page.locator("[data-testid=agent-card][data-group=doomed]");
                assertThat(card).isVisible();
                assertThat(card.locator("[data-testid=agent-card-count]")).hasText("1");
                b.shot("agents-list-doomed-looks-normal");
                card.locator("[data-testid=agent-open]").click();
                // only the agent detail shows the hint: the thread is TERMINATED
                assertThat(b.byTestId("agent-thread-state")).hasText("TERMINATED");
                b.shot("agent-detail-terminated");
                assertTrue(b.command("control.group.status", "doomed").contains("state=DEAD"));
            });
        }
    }

    @Test
    void c_incompleteShutdown(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            var blocker = Scenarios.blockingStop(s);
            ui(info, s, b -> {
                b.open();
                // CURRENT (missing): nothing says the server is STOPPING with an incomplete StopResult;
                // the overview simply shows no processors
                assertThat(b.page.locator("[data-testid=overview-processor-row]")).hasCount(0);
                b.shot("overview-during-incomplete-stop");
                String status = b.command("control.group.status", "slowstop");
                assertTrue(status.contains("state=CLOSING"), status);
                b.shot("commands-group-closing");
            });
            blocker.release.countDown();
            assertTrue(s.server.shutdown().complete());
        }
    }

    @Test
    void d_captureCloseFailureAndLimit(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.captureCloseFailure(s);
            ui(info, s, b -> {
                b.open();
                assertTrue(b.command("audit.start", "rec").contains("audit: RECORDING rec"));
                b.open();
                // fixed on this branch: the overview used to count 0 recording processors whatever the server said
                assertThat(b.processorRow("cap/rec").locator("[data-testid=overview-processor-audit]")).hasText("recording");
                assertThat(b.byTestId("overview-audit-recording")).hasText("1 processor(s)");
                b.shot("overview-recording");
                assertTrue(b.command("audit.stop", "rec").contains("audit: STOPPED rec"));
                ScenarioServer.awaitTrue("close failed", () -> !s.server.controlGroupStatus("cap").captureCloseFailures().isEmpty(), 5_000);
                b.open();
                // CURRENT (misleading): a capture whose physical close FAILED looks like a finished file
                assertThat(b.processorRow("cap/rec").locator("[data-testid=overview-processor-audit]")).hasText("off");
                assertThat(b.byTestId("overview-audit-files")).hasText("1");
                b.shot("overview-close-failed-looks-fine");
                String limit = b.command("audit.start", "rec2");
                assertTrue(limit.contains("physical capture resource limit reached"), limit);
                b.shot("commands-limit-refused");
                assertTrue(b.command("control.group.status", "cap").contains("captureCloseFailures={1="));
            });
        }
    }

    @Test
    void e_overload(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            long dropped = Scenarios.overload(s);
            assertTrue(dropped > 0);
            ui(info, s, b -> {
                b.open();
                assertThat(b.processorRow("slow/consumer")).isVisible();
                // CURRENT (missing): nothing on the overview or the queues view says items were dropped
                String visible = b.page.locator("body").innerText().toLowerCase();
                assertFalse(visible.contains("dropped") || visible.contains("degraded"), "no loss indication on the overview");
                b.shot("overview-no-loss-shown");
                b.byTestId("nav-managed").click();
                assertThat(b.page.locator("[data-testid=agent-card][data-group=slow]")).isVisible();
                assertFalse(b.page.locator("body").innerText().toLowerCase().contains("dropped"));
                b.shot("managed-services-no-loss-shown");
                // CURRENT (shown but misleading): the per-queue drop counter appears only as an anonymous
                // "app counter" named queue.<path>.dropped, with no health, severity or first-loss link
                b.byTestId("nav-app-counters").click();
                var drop = b.page.locator("[data-testid=app-counter-row][data-counter$=\".dropped\"]");
                assertThat(drop).hasCount(1);
                assertThat(drop).hasAttribute("data-counter",
                        java.util.regex.Pattern.compile("^queue\\./feed/feed/group/slow/subscriber/.*\\.dropped$"));
                assertThat(drop.locator("[data-testid=app-counter-total]")).not().hasText("0");
                b.shot("app-counters-raw-drop-counter");
            });
        }
    }

    @Test
    void f_refusedRequests(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            var busy = Scenarios.fullMailbox(s);
            ui(info, s, b -> {
                b.open();
                String refused = b.command("server.processors.stop", "busy/consumer");
                assertTrue(refused.contains("stop REFUSED: group busy: processor-stop queue stayed full (4096)"), refused);
                b.shot("commands-mailbox-full");
                // CURRENT (misleading): a stop of something that does not exist reads as pending
                assertTrue(b.command("server.processors.stop", "nosuch/x").contains("stop pending (still applies)"));
                b.shot("commands-stop-unknown-pending");
            });
            busy.parker().release.countDown();
        }
    }

    @Test
    void g_captureSelectors(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.duplicateNames(s);
            ui(info, s, b -> {
                b.open();
                String amb = b.command("audit.start", "pricer");
                assertTrue(amb.contains("processor 'pricer' is ambiguous: use one of [gA/pricer, gB/pricer]"), amb);
                b.shot("commands-ambiguous");
                assertTrue(b.command("audit.start", "gA/pricer").contains("RECORDING gA/pricer"));
                b.open();
                // fixed on this branch: rows match the qualified selector, so only gA/pricer is recording
                assertThat(b.processorRow("gA/pricer").locator("[data-testid=overview-processor-audit]")).hasText("recording");
                assertThat(b.processorRow("gB/pricer").locator("[data-testid=overview-processor-audit]")).hasText("off");
                b.shot("overview-qualified-recording");
            });
        }
    }

    @Test
    void h_replayStatus(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            ui(info, s, b -> {
                b.open();
                b.byTestId("nav-commands").click();
                b.byTestId("cmd-filter").fill("replay");
                // CURRENT (not reachable): no command or view shows Mongoose record/replay status
                assertThat(b.page.locator("[data-testid=cmd-item]")).hasCount(0);
                b.shot("commands-no-replay");
            });
        }
    }
}
