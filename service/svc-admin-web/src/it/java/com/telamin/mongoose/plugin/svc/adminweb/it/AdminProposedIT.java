/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The REST behaviour docs/failure-ux-proposal.md asks for, one test per gap the validation classified as
 * missing or misleading. Tagged "proposed": excluded from {@code -Padmin-web-it}, run with
 * {@code -Dgroups=proposed}, and expected to FAIL until the referenced section lands. They are the acceptance
 * criteria, not a weakened copy of the current-behaviour pack ({@link AdminRestScenarioIT}).
 *
 * <p>Landed in milestone 1 and moved, as real assertions, into {@link AdminRestScenarioIT}: the quarantine issue
 * and the quarantined processor kept on {@code /api/agents} (a), the DEAD group (b), the STOPPING state and
 * StopResult on {@code /api/server} (c), queue drop count and health (e). svc-admin-rest answering every reply
 * line was released in plugins 1.0.46 (a). What is left needs a Mongoose change.
 */
@Tag("proposed")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AdminProposedIT {

    /** Proposal §4.4: a capture whose physical close failed is flagged, not listed as an ordinary finished file. */
    @Test
    void failedCaptureCloseIsFlagged() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.captureCloseFailure(s);
            s.command("audit.start", "rec");
            s.command("audit.stop", "rec");
            ScenarioServer.awaitTrue("close failed", () -> !s.server.controlGroupStatus("cap").captureCloseFailures().isEmpty(), 5_000);
            String files = s.get("/api/audit/files").body();
            assertTrue(files.contains("\"closeFailed\":true"), files);
        }
    }

    /**
     * Proposal §4.5: first-loss evidence on the queue view. The drop count and DEGRADED health landed in M1
     * ({@link AdminRestScenarioIT#e_queueOverloadDrops}); the first QueueDrop record needs Mongoose A3 / #77.
     */
    @Test
    void queueFirstLossIsVisible() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.overload(s);
            String queues = s.get("/api/queues").body();
            assertTrue(queues.contains("\"firstLoss\":{"), queues);
        }
    }

    // Unknown-stop refusal is now asserted by AdminRestScenarioIT.f_refusedControlRequests.

    /** Proposal §4.8: replay status is readable (experimental). */
    @Test
    void replayStatusIsExposed() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            assertEquals(200, s.get("/api/replay").statusCode());
        }
    }
}
