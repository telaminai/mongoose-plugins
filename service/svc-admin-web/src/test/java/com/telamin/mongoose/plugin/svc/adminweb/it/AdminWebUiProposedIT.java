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

/**
 * The UI docs/failure-ux-proposal.md asks for. Tagged "proposed": excluded from {@code -Padmin-web-it} and
 * expected to FAIL until the referenced section lands ({@code -Dit.groups=proposed -Dit.excludedGroups=none}).
 * The selectors named here are the contract the implementation should provide.
 */
@Tag("proposed")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AdminWebUiProposedIT {

    @AfterAll
    static void closeBrowser() {
        Browser.shutdown();
    }

    /** Proposal §3, §4.1: the issues panel shows a CRITICAL card for the quarantined processor, with its impact. */
    @Test
    void quarantineCard(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.quarantine(s);
            AdminWebUiScenarioIT.ui(info, s, b -> {
                b.open();
                var card = b.page.locator("[data-testid=issue-card][data-kind=PROCESSOR_QUARANTINED][data-subject=\"pricing/pricer\"]");
                assertThat(card).isVisible();
                assertThat(card.locator("[data-testid=issue-severity]")).hasText("CRITICAL");
                assertThat(card.locator("[data-testid=issue-impact]")).containsText("no output from pricing/pricer");
                // acknowledging never offers to mark the cleanup done (#77)
                assertThat(card.locator("[data-testid=issue-action][data-action=mark-resolved]")).hasCount(0);
            });
        }
    }

    /** Proposal §4.2: a dead group is badged DEAD on the agents list. */
    @Test
    void deadGroupBadge(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.deadGroup(s);
            AdminWebUiScenarioIT.ui(info, s, b -> {
                b.open();
                b.byTestId("nav-managed").click();
                assertThat(b.page.locator("[data-testid=agent-card][data-group=doomed] [data-testid=agent-control-state]")).hasText("DEAD");
            });
        }
    }

    /** Proposal §4.3: a shutdown banner with the StopResult while the server is STOPPING. */
    @Test
    void incompleteStopBanner(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            var blocker = Scenarios.blockingStop(s);
            AdminWebUiScenarioIT.ui(info, s, b -> {
                b.open();
                assertThat(b.byTestId("stop-result-banner")).containsText("groups still running: slowstop");
            });
            blocker.release.countDown();
        }
    }

    /** Proposal §4.4: a capture whose physical close failed is flagged on the overview. */
    @Test
    void closeFailedPill(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.captureCloseFailure(s);
            s.command("audit.start", "rec");
            s.command("audit.stop", "rec");
            ScenarioServer.awaitTrue("close failed", () -> !s.server.controlGroupStatus("cap").captureCloseFailures().isEmpty(), 5_000);
            AdminWebUiScenarioIT.ui(info, s, b -> {
                b.open();
                assertThat(b.processorRow("cap/rec").locator("[data-testid=overview-processor-audit]")).hasText("close failed");
            });
        }
    }

    /** Proposal §4.5: queue loss is a DEGRADED issue with a link to the first loss. */
    @Test
    void queueLossCard(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.overload(s);
            AdminWebUiScenarioIT.ui(info, s, b -> {
                b.open();
                var card = b.page.locator("[data-testid=issue-card][data-kind=QUEUE_LOSS]");
                assertThat(card).isVisible();
                assertThat(card.locator("[data-testid=issue-evidence]")).containsText("first loss");
            });
        }
    }
}
