/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb.it;

import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.control.ControlDriver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static com.telamin.mongoose.plugin.svc.adminweb.it.ScenarioServer.awaitTrue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * REST regression pack: one test per failure scenario (a-h of docs/failure-ux-proposal.md), driven against a
 * real Mongoose 1.0.33-rc1 server with svc-admin-web and svc-admin-rest on 127.0.0.1. Each test pins the exact
 * current JSON contract (golden file, volatile values normalised) and asserts the failure facts explicitly.
 *
 * <p>These tests describe CURRENT behaviour, including behaviour the proposal classifies as misleading or
 * missing; each such assertion says so. The desired behaviour is in {@link AdminProposedIT} (tag "proposed").
 */
@Tag("rest-it")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AdminRestScenarioIT {

    @Test
    void a_generatedProcessorQuarantinedAndHandwrittenThrows() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.quarantine(s);
            Golden g = new Golden("a-quarantine", s);

            String status = body(g.record("control.group.status pricing", s.command("control.group.status", "pricing")));
            assertTrue(status.contains("state=RUNNING"), status);
            assertTrue(status.contains("quarantinedProcessors={pricer=com.telamin.mongoose.dutycycle.ProcessorDispatchException"), status);
            // handwritten failure: nothing is contained and nothing is reported on any admin surface
            assertTrue(body(g.record("control.group.status hand", s.command("control.group.status", "hand")))
                    .contains("quarantinedProcessors={}"));
            // CURRENT (misleading): the quarantined processor silently disappears from both listings
            String list = body(g.record("server.processors.list", s.command("server.processors.list")));
            assertFalse(list.contains("pricing/pricer"), list);
            assertTrue(list.contains("pricing/peer"), list);
            String agents = body(g.record("GET /api/agents", s.get("/api/agents")));
            assertFalse(agents.contains("\"name\":\"pricer\""), agents);
            String stop = body(g.record("server.processors.stop pricing/pricer", s.command("server.processors.stop", "pricing/pricer")));
            assertTrue(stop.contains("stop FAILED: processor quarantined; lifecycle cleanup requires operator resolution"), stop);
            // svc-admin-rest: /admin keeps only the LAST reply line; /api/{action} cannot pass arguments
            g.record("REST POST /admin stop", s.postRest("/admin",
                    "{\"command\":\"server.processors.stop\",\"arguments\":[\"pricing/pricer\"]}"));
            assertTrue(body(g.record("REST POST /api/control.group.status", s.postRest("/api/control.group.status", "")))
                    .contains("usage: control.group.status <group>"));
            g.verify();

            StopResult stopResult = s.server.shutdown();
            assertFalse(stopResult.complete(), "a quarantined graph keeps shutdown incomplete");
            assertTrue(stopResult.groupsRunning().contains("pricing"), stopResult.toString());
        }
    }

    @Test
    void b_deadGroup() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            var errors = Scenarios.deadGroup(s);
            assertEquals(1, errors.size());
            Golden g = new Golden("b-dead-group", s);

            assertTrue(body(g.record("control.group.status doomed", s.command("control.group.status", "doomed"))).contains("state=DEAD"));
            // CURRENT (misleading): the dead group's processor is still listed as if it ran
            assertTrue(body(g.record("server.processors.list", s.command("server.processors.list"))).contains("doomed/ok"));
            String agents = body(g.record("GET /api/agents", s.get("/api/agents")));
            assertTrue(agents.contains("\"name\":\"ok\""), agents);
            assertTrue(agents.contains("\"threadState\":\"TERMINATED\""), agents);
            assertTrue(body(g.record("server.processors.stop doomed/ok", s.command("server.processors.stop", "doomed/ok")))
                    .contains("stop NOT_RUNNING: group doomed is DEAD"));
            g.verify();
        }
    }

    @Test
    void c_incompleteShutdownThenResume() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            var blocker = Scenarios.blockingStop(s);
            StopResult first = s.server.lastStopResult();
            assertFalse(first.complete());
            assertEquals(java.util.List.of("slowstop"), first.groupsRunning());
            assertTrue(first.servicesLeftRunning().contains("adminWebService"), "the web admin outlives an incomplete stop");
            Golden g = new Golden("c-incomplete-shutdown", s);

            assertEquals("OK", body(g.record("GET /healthz", s.get("/healthz"))));
            // CURRENT (misleading): a CLOSING group looks like a new, unstarted, empty group
            String agents = body(g.record("GET /api/agents", s.get("/api/agents")));
            assertTrue(agents.contains("\"members\":[]") && agents.contains("\"threadState\":\"NEW\""), agents);
            String status = body(g.record("control.group.status slowstop", s.command("control.group.status", "slowstop")));
            assertTrue(status.contains("state=CLOSING") && status.contains("outstandingRequests=1"), status);
            // CURRENT (missing): no surface returns the StopResult or the server's STOPPING state
            String server = body(g.record("GET /api/server", s.get("/api/server")));
            assertFalse(server.contains("STOPPING") || server.contains("complete") || server.contains("groupsRunning"), server);
            assertEquals(404, g.record("GET /api/shutdown", s.get("/api/shutdown")).statusCode());

            blocker.release.countDown();
            StopResult second = s.server.shutdown();
            assertTrue(second.complete(), "resume completes once the blocker can progress: " + second);
            g.verify();
        }
    }

    @Test
    void d_failedCaptureCloseAndResourceLimit() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.captureCloseFailure(s);
            Golden g = new Golden("d-capture-close", s);

            assertTrue(body(g.record("audit.start rec", s.command("audit.start", "rec"))).contains("audit: RECORDING rec"));
            String files = body(g.record("GET /api/audit/files (recording)", s.get("/api/audit/files")));
            assertTrue(files.contains("\"processorName\":\"cap/rec\"") && files.contains("\"isLive\":true"), files);
            assertTrue(body(g.record("audit.stop rec", s.command("audit.stop", "rec"))).contains("audit: STOPPED rec"),
                    "a logical stop reports success although the physical close failed");
            awaitTrue("close failure recorded", () -> !s.server.controlGroupStatus("cap").captureCloseFailures().isEmpty(), 5_000);
            String status = body(g.record("control.group.status cap", s.command("control.group.status", "cap")));
            assertTrue(status.contains("captureResourceSlotsInUse=1"), status);
            assertTrue(status.contains("captureCloseFailures={1=java.lang.IllegalStateException: forced capture close failure}"), status);
            String limit = body(g.record("audit.start rec2 (limit)", s.command("audit.start", "rec2")));
            assertTrue(limit.contains("open failed: physical capture resource limit reached"), limit);
            // fixed on this branch: was a bare 500 "Server Error"
            HttpResponse<String> rest = g.record("POST /api/audit/rec2/start (limit)", s.postWeb("/api/audit/rec2/start", "{}"));
            assertEquals(409, rest.statusCode(), rest.body());
            assertTrue(rest.body().contains("physical capture resource limit reached"), rest.body());
            // CURRENT (misleading): the failed close is listed as an ordinary finished capture
            String list = body(g.record("audit.list", s.command("audit.list")));
            assertTrue(list.contains("selector=cap/rec") && !list.contains("CLOSE_FAILED"), list);
            g.record("audit.status", s.command("audit.status"));
            g.verify();

            StopResult stop = s.server.shutdown();
            assertFalse(stop.complete());
            assertEquals(java.util.List.of("cap"), stop.groupsRunning());
        }
    }

    @Test
    void e_queueOverloadDrops() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            long dropped = Scenarios.overload(s);
            assertTrue(dropped > 0);
            Golden g = new Golden("e-overload", s);

            String queues = body(g.record("GET /api/queues", s.get("/api/queues")));
            // CURRENT (missing): the queue view has no drop count, health or first-loss evidence
            assertFalse(queues.contains("drop"), queues);
            g.record("control.group.status slow", s.command("control.group.status", "slow"));
            assertFalse(body(g.record("GET /api/commands", s.get("/api/commands"))).contains("health"),
                    "no admin command exposes the queue-drops DEGRADED health check");
            assertEquals(404, g.record("GET /api/health", s.get("/api/health")).statusCode());
            g.verify();
        }
    }

    @Test
    void f_refusedControlRequests() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Golden g = new Golden("f-refusals", s);
            s.add("busy", "consumer", Fixtures.Healthy::new);
            awaitTrue("busy running", () -> s.server.controlGroupStatus("busy").state() == ControlDriver.GroupState.RUNNING, 5_000);
            s.server.stopProcessor("busy", "consumer", 2_000);
            // CURRENT (misleading): stopping something that does not exist prints "stop pending (still applies)"
            assertTrue(body(g.record("server.processors.stop nosuch/x", s.command("server.processors.stop", "nosuch/x")))
                    .contains("stop pending (still applies): nosuch/x"));
            // CURRENT (defect in Mongoose admin): a missing argument is an IndexOutOfBounds
            assertTrue(body(g.record("server.processors.stop (no args)", s.command("server.processors.stop")))
                    .contains("exception: Index 1 out of bounds for length 1"));
            g.verify();
        }
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Golden g = new Golden("f-mailbox-full", s);
            var busy = Scenarios.fullMailbox(s);
            String refused = body(g.record("server.processors.stop busy/consumer (mailbox full)",
                    s.command("server.processors.stop", "busy/consumer")));
            // the admin path's reason is "processor-stop queue stayed full (4096) for <timeout> ms", while the handle
            // path (and the contract table) says "mailbox full": the same refusal has two wordings
            assertTrue(refused.contains("stop REFUSED: group busy: processor-stop queue stayed full (4096) for 100 ms"), refused);
            busy.parker().release.countDown();
            g.verify();
        }
    }

    @Test
    void g_captureSelectors() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Scenarios.duplicateNames(s);
            Golden g = new Golden("g-selectors", s);

            assertTrue(body(g.record("audit.start pricer (ambiguous)", s.command("audit.start", "pricer")))
                    .contains("processor 'pricer' is ambiguous: use one of [gA/pricer, gB/pricer]"));
            HttpResponse<String> amb = g.record("POST /api/audit/pricer/start (ambiguous)", s.postWeb("/api/audit/pricer/start", "{}"));
            assertEquals(404, amb.statusCode());
            assertTrue(body(g.record("audit.start gA/pricer", s.command("audit.start", "gA/pricer"))).contains("RECORDING gA/pricer"));
            HttpResponse<String> encoded = g.record("POST /api/audit/gB%2Fpricer/start", s.postWeb("/api/audit/gB%2Fpricer/start", "{}"));
            assertEquals(200, encoded.statusCode());
            assertTrue(encoded.body().contains("\"recording\":true"), encoded.body());
            assertEquals(404, g.record("POST /api/audit/gB/pricer/start (raw slash)", s.postWeb("/api/audit/gB/pricer/start", "{}")).statusCode());
            String files = body(g.record("GET /api/audit/files", s.get("/api/audit/files")));
            assertTrue(files.contains("\"processorName\":\"gA/pricer\"") && files.contains("\"processorName\":\"gB/pricer\""), files);
            // fixed on this branch: an ambiguous stop was a bare 500
            HttpResponse<String> stopAmb = g.record("POST /api/audit/pricer/stop (ambiguous)", s.postWeb("/api/audit/pricer/stop", "{}"));
            assertEquals(404, stopAmb.statusCode(), stopAmb.body());
            assertTrue(body(g.record("audit.stop gA/pricer", s.command("audit.stop", "gA/pricer"))).contains("STOPPED gA/pricer"));
            g.verify();
        }
    }

    @Test
    void h_replayStatusNotExposed() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Golden g = new Golden("h-replay", s);
            String commands = body(g.record("GET /api/commands", s.get("/api/commands")));
            assertFalse(commands.contains("replay"), commands);
            assertEquals(404, g.record("GET /api/replay", s.get("/api/replay")).statusCode());
            assertTrue(s.server.replayers().isEmpty());
            g.verify();
        }
    }

    private static String body(HttpResponse<String> r) {
        return r.body();
    }
}
