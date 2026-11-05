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
 *
 * <p>Landed in milestone 1 and moved into {@link AdminWebUiScenarioIT}: the quarantine card (a), the DEAD badge
 * (b), the incomplete-stop banner (c) and the queue loss card (e). What is left needs a Mongoose change.
 */
@Tag("proposed")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AdminWebUiProposedIT {

    @AfterAll
    static void closeBrowser() {
        Browser.shutdown();
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

    /** Proposal §4.5: the queue loss card links to the first loss (needs Mongoose A3 / #77); the card itself landed in M1. */
    @Test
    void queueLossFirstLossLink(TestInfo info) throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.overload(s);
            AdminWebUiScenarioIT.ui(info, s, b -> {
                b.open();
                var card = b.page.locator("[data-testid=issue-card][data-kind=QUEUE_LOSS]");
                assertThat(card.locator("[data-testid=issue-first-loss]")).isVisible();
            });
        }
    }
}
