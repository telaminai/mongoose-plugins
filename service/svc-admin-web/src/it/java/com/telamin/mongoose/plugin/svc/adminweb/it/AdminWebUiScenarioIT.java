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

    static com.microsoft.playwright.Locator card(Browser b, String kind, String subject) {
        return b.page.locator("[data-testid=issue-card][data-kind=" + kind + "]"
                + (subject == null ? "" : "[data-subject=\"" + subject + "\"]"));
    }

    @Test
    void a_quarantine(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.quarantine(s, true);
            ui(info, s, b -> {
                b.open();
                assertThat(b.processorRow("pricing/peer")).isVisible();
                assertThat(b.processorRow("hand/handwritten")).isVisible();
                // M1 (proposal §4.1): the quarantined processor stays on the overview, marked
                assertThat(b.processorRow("pricing/pricer").locator("[data-testid=overview-processor-state]")).hasText("QUARANTINED");
                // M1 (proposal §3): the header pill reports the worst open issue, not just the connection
                assertThat(b.byTestId("issues-pill")).hasAttribute("data-worst", "CRITICAL");
                assertThat(b.byTestId("issues-strip-worst")).hasText("CRITICAL");
                var card = card(b, "PROCESSOR_QUARANTINED", "pricing/pricer");
                assertThat(card).isVisible();
                assertThat(card.locator("[data-testid=issue-severity]")).hasText("CRITICAL");
                assertThat(card.locator("[data-testid=issue-impact]")).containsText("no output from pricing/pricer");
                assertThat(card.locator("[data-testid=issue-what]")).containsText("ProcessorDispatchException: generated processor execution failed");
                assertThat(card.locator("[data-testid=issue-evidence-unavailable]")).containsText("not available here");
                // evidence: the processor's capture, downloadable for the analyser
                var link = card.locator("[data-testid=issue-evidence-link]");
                assertThat(link).hasText("Download for the analyser");
                String href = link.getAttribute("href");
                assertTrue(href.startsWith("/api/audit/file/") && href.endsWith("/export"), href);
                var download = s.get(href);
                assertEquals(200, download.statusCode());
                assertTrue(download.body().contains("---"), "a YAML capture export");
                // no action may manufacture success (#77); replace and stop are explained, not offered
                assertThat(card.locator("[data-testid=issue-action][data-action=mark-resolved]")).hasCount(0);
                assertThat(card.locator("[data-testid=issue-action][data-action=replace]")).isDisabled();
                assertThat(card.locator("[data-testid=issue-action][data-action=stop]")).isDisabled();
                b.shot("overview-quarantine-card");
                // export evidence runs control.audit.export into a new directory and reports completeness
                card.locator("[data-testid=issue-action][data-action=export-evidence]").click();
                card.locator("[data-testid=issue-export-dir]").fill(s.workDir.resolve("evidence-pricing").toString());
                card.locator("[data-testid=issue-export-run]").click();
                var output = card.locator("[data-testid=issue-export-output]");
                for (int i = 0; i < 20 && !output.innerText().contains("completeness="); i++) {
                    b.page.waitForTimeout(250);
                    card.locator("[data-testid=issue-export-status]").click();
                }
                assertThat(output).containsText("completeness=");
                assertTrue(java.nio.file.Files.isDirectory(s.workDir.resolve("evidence-pricing")));
                b.shot("quarantine-evidence-exported");
                // acknowledging moves the card; it stays open and the pill stays CRITICAL
                card.locator("[data-testid=issue-action][data-action=acknowledge]").click();
                assertThat(card).hasAttribute("data-acknowledged", "true");
                assertThat(b.page.locator("[data-testid=issues-section][data-section=ACK] [data-testid=issue-card][data-kind=PROCESSOR_QUARANTINED]")).isVisible();
                assertThat(b.byTestId("issues-pill")).hasAttribute("data-worst", "CRITICAL");
                b.shot("quarantine-acknowledged-still-open");
                String stop = b.command("server.processors.stop", "pricing/pricer");
                assertTrue(stop.contains("stop FAILED (QUARANTINED): processor quarantined"), stop);
                b.open();
                assertThat(card(b, "REQUEST_FAILED", "pricing/pricer").locator("[data-testid=issue-outcome]")).hasText("FAILED");
                b.shot("stop-failed-recorded");
            });
        }
    }

    @Test
    void b_deadGroup(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.deadGroup(s);
            ui(info, s, b -> {
                b.open();
                // M1 (proposal §4.2): the dead group's processor is marked, and the group is an issue
                assertThat(b.processorRow("doomed/ok").locator("[data-testid=overview-processor-state]")).hasText("group DEAD");
                var issue = card(b, "GROUP_DEAD", "doomed");
                assertThat(issue.locator("[data-testid=issue-failure-kind]")).hasText("FAILED_DEAD");
                assertThat(issue.locator("[data-testid=issue-impact]")).containsText("No processor in doomed runs");
                assertThat(issue.locator("[data-testid=issue-action][data-action=restart-process]")).isDisabled();
                b.shot("overview-dead-group-card");
                b.byTestId("nav-managed").click();
                var agent = b.page.locator("[data-testid=agent-card][data-group=doomed]");
                assertThat(agent.locator("[data-testid=agent-control-state]")).hasText("DEAD");
                assertThat(agent.locator("[data-testid=agent-card-count]")).hasText("1");
                b.shot("agents-list-doomed-dead");
                agent.locator("[data-testid=agent-open]").click();
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
                // M1 (proposal §4.3): a banner with the StopResult, and a CRITICAL issue
                assertThat(b.byTestId("stop-result-banner")).containsText("groups with incomplete cleanup: slowstop");
                assertThat(b.byTestId("stop-result-banner")).containsText("Server STOPPING");
                assertThat(b.byTestId("stop-result-banner")).containsText("FAILED_SHUTDOWN");
                var issue = card(b, "SHUTDOWN_INCOMPLETE", "server");
                assertThat(issue.locator("[data-testid=issue-evidence]")).containsText("state CLOSING, outstanding requests 1");
                assertThat(issue.locator("[data-testid=issue-action][data-action=retry-stop]")).isDisabled();
                // Mongoose still drops a CLOSING group's processors from its registry (proposal A11)
                assertThat(b.page.locator("[data-testid=overview-processor-row]")).hasCount(0);
                b.shot("overview-incomplete-stop-banner");
                b.byTestId("nav-managed").click();
                assertThat(b.byTestId("stop-result-banner")).isVisible();
                assertThat(b.page.locator("[data-testid=agent-card][data-group=slowstop] [data-testid=agent-control-state]")).hasText("CLOSING");
                b.shot("agents-closing-banner-on-every-view");
                String status = b.command("control.group.status", "slowstop");
                assertTrue(status.contains("state=CLOSING"), status);
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
                assertThat(b.processorRow("cap/rec").locator("[data-testid=overview-processor-audit]")).hasText("recording");
                assertThat(b.byTestId("overview-audit-recording")).hasText("1 processor(s)");
                b.shot("overview-recording");
                assertTrue(b.command("audit.stop", "rec").contains("audit: STOPPED rec"));
                ScenarioServer.awaitTrue("close failed", () -> !s.server.controlGroupStatus("cap").captureCloseFailures().isEmpty(), 5_000);
                b.open();
                // M1 (proposal §4.4): the failed close is an issue, by resource id
                var issue = card(b, "CAPTURE_CLOSE_FAILED", "cap resource 1");
                assertThat(issue.locator("[data-testid=issue-severity]")).hasText("DEGRADED");
                assertThat(issue.locator("[data-testid=issue-evidence]")).containsText("forced capture close failure");
                assertThat(issue.locator("[data-testid=issue-evidence-unavailable]").first()).containsText("Which capture");
                assertThat(issue.locator("[data-testid=issue-action][data-action=retry-close]")).isDisabled();
                // which capture the resource was is not exposed on 1.0.33 (A2): the row still reads "off"
                assertThat(b.processorRow("cap/rec").locator("[data-testid=overview-processor-audit]")).hasText("off");
                b.shot("overview-close-failed-card");
                String limit = b.command("audit.start", "rec2");
                assertTrue(limit.contains("physical capture resource limit reached"), limit);
                b.open();
                var refused = card(b, "REQUEST_REFUSED", "rec2");
                assertThat(refused.locator("[data-testid=issue-impact]")).containsText("A capture slot is held");
                b.shot("overview-limit-refused-card");
                // the verify action opens the capture file list
                issue.locator("[data-testid=issue-action][data-action=open-captures]").click();
                assertThat(b.page.locator("section.view[x-show=\"activeView==='replay'\"]")).isVisible();
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
                // M1 (proposal §4.5): queue loss is a DEGRADED issue with the count; first loss is not live on 1.0.33
                var issue = card(b, "QUEUE_LOSS", "feed → slow");
                assertThat(issue.locator("[data-testid=issue-severity]")).hasText("DEGRADED");
                assertThat(issue.locator("[data-testid=issue-evidence]")).containsText("Dropped: " + dropped);
                assertThat(issue.locator("[data-testid=issue-evidence-unavailable]")).containsText("first loss");
                assertThat(issue.locator("[data-testid=issue-action][data-action=clear]")).isDisabled();
                // exact: queue loss is the worst fact here, so the pill is DEGRADED, not CRITICAL
                assertThat(b.byTestId("issues-pill")).hasAttribute("data-worst", "DEGRADED");
                assertThat(b.byTestId("ws-pill")).not().hasClass(java.util.regex.Pattern.compile(".*pill-ok.*"));
                b.shot("overview-queue-loss-card");
                b.byTestId("nav-managed").click();
                b.page.locator(".integration-tab", new com.microsoft.playwright.Page.LocatorOptions().setHasText("Queues")).click();
                b.page.locator("button", new com.microsoft.playwright.Page.LocatorOptions().setHasText("Expand all")).click();
                assertThat(b.page.locator("[data-testid=queue-dropped]:visible")).hasText(dropped + " dropped");
                assertThat(b.page.locator("[data-testid=queue-health]:visible")).hasText("DEGRADED");
                b.shot("queues-dropped-and-health");
                // still CURRENT: the raw counter also appears under App counters
                b.byTestId("nav-app-counters").click();
                var drop = b.page.locator("[data-testid=app-counter-row][data-counter$=\".dropped\"]");
                assertThat(drop).hasCount(1);
                assertThat(drop).hasAttribute("data-counter",
                        java.util.regex.Pattern.compile("^queue\\./feed/feed/group/slow/subscriber/.*\\.dropped$"));
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
                assertTrue(refused.contains("stop REFUSED (REFUSED_ADMISSION): group busy: processor-stop request refused: mailbox full (capacity 4096)"), refused);
                b.shot("commands-mailbox-full");
                // 1.1.0 refuses unknown names explicitly.
                assertTrue(b.command("server.processors.stop", "nosuch/x").contains("stop REFUSED (REFUSED_STATE): no such group"));
                b.open();
                // M1 (proposal §4.6): both are INFO issues with the reason and a hint
                var full = card(b, "REQUEST_REFUSED", "busy/consumer");
                assertThat(full.locator("[data-testid=issue-outcome]")).hasText("REFUSED");
                assertThat(full.locator("[data-testid=issue-impact]")).containsText("Refusal kind: REFUSED_ADMISSION");
                var unknown = card(b, "REQUEST_REFUSED", "nosuch/x");
                assertThat(unknown.locator("[data-testid=issue-outcome]")).hasText("REFUSED");
                assertThat(unknown.locator("[data-testid=issue-impact]")).containsText("Refusal kind: REFUSED_STATE");
                // the kind filter retains both explicit refusals
                b.byTestId("issues-filter-kind").selectOption("REQUEST_REFUSED");
                assertThat(b.page.locator("[data-testid=issue-card]")).hasCount(2);
                b.shot("overview-refusal-cards");
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
                // M1: a clean server is "no known issues", never green, and lists what cannot be observed
                b.open();
                assertThat(b.page.locator("[data-testid=issue-card]")).hasCount(0);
                assertThat(b.byTestId("issues-pill")).hasText("No known issues");
                assertThat(b.byTestId("issues-pill")).hasAttribute("data-worst", "NONE");
                assertThat(b.byTestId("issues-strip-worst")).hasText("NO KNOWN ISSUES");
                assertThat(b.byTestId("issues-empty")).containsText("not a clean bill of health");
                b.byTestId("issues-gaps-toggle").click();
                assertThat(b.page.locator("[data-testid=issue-gap]", new com.microsoft.playwright.Page.LocatorOptions().setHasText("Replay status"))).isVisible();
                assertThat(b.page.locator("[data-testid=issue-gap]", new com.microsoft.playwright.Page.LocatorOptions().setHasText("Handwritten handler failures"))).isVisible();
                b.shot("overview-no-known-issues-with-gaps");
                // a failed poll never leaves "No known issues" standing as current
                b.page.route("**/api/issues", route -> route.abort("aborted"));
                assertThat(b.byTestId("issues-pill")).containsText("Issues unavailable since",
                        new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(8_000));
                assertThat(b.byTestId("issues-stale")).isVisible();
                b.shot("issues-unavailable-stale");
                b.page.unroute("**/api/issues");
                assertThat(b.byTestId("issues-pill")).hasText("No known issues",
                        new com.microsoft.playwright.assertions.LocatorAssertions.HasTextOptions().setTimeout(8_000));
            });
        }
    }
}
