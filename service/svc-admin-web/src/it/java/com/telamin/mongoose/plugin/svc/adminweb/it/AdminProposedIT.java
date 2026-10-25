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
 */
@Tag("proposed")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AdminProposedIT {

    /** Proposal §3 and §4.1: an issues endpoint names the quarantined processor, its impact and evidence. */
    @Test
    void quarantinedProcessorIsAnIssue() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.quarantine(s);
            String issues = s.get("/api/issues").body();
            assertTrue(issues.contains("\"kind\":\"PROCESSOR_QUARANTINED\""), issues);
            assertTrue(issues.contains("\"subject\":\"pricing/pricer\""), issues);
            assertTrue(issues.contains("\"severity\":\"CRITICAL\""), issues);
            assertFalse(issues.contains("\"action\":\"acknowledge-and-clear\""), "no action may manufacture success");
        }
    }

    /** Proposal §4.1: the agents view keeps a quarantined processor, marked, instead of dropping it. */
    @Test
    void quarantinedProcessorStaysListedWithItsState() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.quarantine(s);
            String agents = s.get("/api/agents").body();
            assertTrue(agents.contains("\"name\":\"pricer\""), agents);
            assertTrue(agents.contains("\"state\":\"QUARANTINED\""), agents);
        }
    }

    /** Proposal §4.2: a dead group says DEAD in the agents view, not a normal member list. */
    @Test
    void deadGroupIsMarkedDead() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.deadGroup(s);
            String agents = s.get("/api/agents").body();
            assertTrue(agents.contains("\"controlState\":\"DEAD\""), agents);
        }
    }

    /** Proposal §4.3: the last StopResult and the STOPPING state are on the surface while the web admin survives. */
    @Test
    void incompleteStopIsReported() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            var blocker = Scenarios.blockingStop(s);
            String server = s.get("/api/server").body();
            assertTrue(server.contains("\"lifecycle\":\"STOPPING\""), server);
            assertTrue(server.contains("\"complete\":false") && server.contains("\"groupsRunning\":[\"slowstop\"]"), server);
            blocker.release.countDown();
        }
    }

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

    /** Proposal §4.5: per-queue drop count, DEGRADED health and first-loss evidence on the queue view. */
    @Test
    void queueDropsAreVisible() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.overload(s);
            String queues = s.get("/api/queues").body();
            assertTrue(queues.contains("\"dropped\":"), queues);
            assertTrue(queues.contains("\"health\":\"DEGRADED\""), queues);
            assertTrue(queues.contains("\"firstLoss\":"), queues);
        }
    }

    /** Proposal §4.6 / §7 (Mongoose): stopping a processor that does not exist is REFUSED, not "pending". */
    @Test
    void stopOfUnknownProcessorIsRefused() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            String out = s.command("server.processors.stop", "nosuch/x").body();
            assertTrue(out.contains("stop REFUSED"), out);
        }
    }

    /** Proposal §4.6: svc-admin-rest answers every reply line, not only the last (fixed on fix/admin-rest-json-and-bad-requests). */
    @Test
    void restAdminKeepsEveryReplyLine() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.quarantine(s);
            String out = s.postRest("/admin", "{\"command\":\"server.processors.stop\",\"arguments\":[\"pricing/pricer\"]}").body();
            assertTrue(out.contains("stopping processor:pricing/pricer") && out.contains("stop FAILED"), out);
        }
    }

    /** Proposal §4.8: replay status is readable (experimental). */
    @Test
    void replayStatusIsExposed() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            assertEquals(200, s.get("/api/replay").statusCode());
        }
    }
}
