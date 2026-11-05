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
 * real Mongoose 1.0.33 server with svc-admin-web and svc-admin-rest on 127.0.0.1. Each test pins the exact
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
            // Mongoose's own listing still drops the quarantined processor (proposal A11)
            String list = body(g.record("server.processors.list", s.command("server.processors.list")));
            assertFalse(list.contains("pricing/pricer"), list);
            assertTrue(list.contains("pricing/peer"), list);
            // M1 (proposal §4.1): the web view keeps it, marked QUARANTINED with its reason
            String agents = body(g.record("GET /api/agents", s.get("/api/agents")));
            assertTrue(agents.contains("{\"name\":\"pricer\",\"kind\":\"processor\",\"className\":\"\",\"subscriptions\":[],\"state\":\"QUARANTINED\""), agents);
            assertTrue(agents.contains("\"controlState\":\"RUNNING\""), agents);
            // M1 (proposal §3, §4.1): a CRITICAL issue with impact and evidence; no action manufactures success
            String issues = body(g.record("GET /api/issues", s.get("/api/issues")));
            assertTrue(issues.contains("\"id\":\"PROCESSOR_QUARANTINED:pricing/pricer\",\"kind\":\"PROCESSOR_QUARANTINED\",\"severity\":\"CRITICAL\",\"subject\":\"pricing/pricer\""), issues);
            assertTrue(issues.contains("\"impact\":\"no output from pricing/pricer until the process is replaced"), issues);
            assertTrue(issues.contains("Other processors in group pricing keep running (peer)"), issues);
            assertTrue(issues.contains("\"action\":\"export-evidence\",\"label\":\"Export evidence\",\"enabled\":true"), issues);
            assertTrue(issues.contains("\"action\":\"replace\",\"label\":\"Replace / re-add\",\"enabled\":false"), issues);
            // exact: the quarantine is the only CRITICAL fact (the harness adds none of its own)
            assertWorst("CRITICAL", issues);
            assertTrue(issues.contains("\"counts\":{\"CRITICAL\":1,\"DEGRADED\":0,\"INFO\":0}"), issues);
            assertFalse(issues.contains("hand/handwritten"), "Mongoose 1.0.33 reports no handwritten failure: " + issues);
            assertTrue(issues.contains("\"fact\":\"Handwritten handler failures\""), "the gap is listed, not hidden: " + issues);
            String stop = body(g.record("server.processors.stop pricing/pricer", s.command("server.processors.stop", "pricing/pricer")));
            assertTrue(stop.contains("stop FAILED (QUARANTINED): processor quarantined; lifecycle cleanup requires operator resolution"), stop);
            // acknowledging moves the issue; it stays open and keeps the worst severity
            assertEquals(200, g.record("POST /api/issues/ack", s.postWeb("/api/issues/ack",
                    "{\"id\":\"PROCESSOR_QUARANTINED:pricing/pricer\"}")).statusCode());
            String acked = body(g.record("GET /api/issues (acknowledged, stop recorded)", s.get("/api/issues")));
            assertTrue(acked.contains("\"failureKind\":\"QUARANTINED\""), acked);
            assertTrue(acked.contains("\"acknowledged\":true"), acked);
            // the only CRITICAL issue is acknowledged; it must still set the worst severity, and stay unacknowledged-count 1 (the INFO stop)
            assertWorst("CRITICAL", acked);
            assertTrue(acked.contains("\"counts\":{\"CRITICAL\":1,\"DEGRADED\":0,\"INFO\":1},\"unacknowledged\":1"), acked);
            assertTrue(acked.contains("\"kind\":\"REQUEST_FAILED\"") && acked.contains("\"outcome\":\"FAILED\""), acked);
            assertEquals(404, g.record("POST /api/issues/ack (unknown)", s.postWeb("/api/issues/ack", "{\"id\":\"nope\"}")).statusCode());
            // svc-admin-rest 1.0.46 (#42) answers every reply line; /api/{action} still cannot pass arguments
            String restStop = body(g.record("REST POST /admin stop", s.postRest("/admin",
                    "{\"command\":\"server.processors.stop\",\"arguments\":[\"pricing/pricer\"]}")));
            assertTrue(restStop.contains("stopping processor:pricing/pricer") && restStop.contains("stop FAILED"), restStop);
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
            // Mongoose's listing still shows the dead group's processor as if it ran
            assertTrue(body(g.record("server.processors.list", s.command("server.processors.list"))).contains("doomed/ok"));
            // M1 (proposal §4.2): the web view marks the group DEAD and its processor not running
            String agents = body(g.record("GET /api/agents", s.get("/api/agents")));
            assertTrue(agents.contains("\"name\":\"ok\""), agents);
            assertTrue(agents.contains("\"threadState\":\"TERMINATED\""), agents);
            assertTrue(agents.contains("\"controlState\":\"DEAD\""), agents);
            assertTrue(agents.contains("\"state\":\"NOT_RUNNING_GROUP_DEAD\""), agents);
            String issues = body(g.record("GET /api/issues", s.get("/api/issues")));
            assertWorst("CRITICAL", issues);
            assertTrue(issues.contains("\"id\":\"GROUP_DEAD:doomed\",\"kind\":\"GROUP_DEAD\",\"severity\":\"CRITICAL\""), issues);
            assertTrue(issues.contains("No processor in doomed runs: ok are listed but not running."), issues);
            assertTrue(issues.contains("\"action\":\"restart-process\""), issues);
            assertTrue(body(g.record("server.processors.stop doomed/ok", s.command("server.processors.stop", "doomed/ok")))
                    .contains("stop NOT_RUNNING (FAILED_DEAD): group doomed is DEAD"));
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
            // M1 (proposal §4.3): the server's STOPPING state and the last StopResult
            String server = body(g.record("GET /api/server", s.get("/api/server")));
            assertTrue(server.contains("\"lifecycle\":\"STOPPING\""), server);
            assertTrue(server.contains("\"complete\":false") && server.contains("\"groupsRunning\":[\"slowstop\"]"), server);
            String issues = body(g.record("GET /api/issues", s.get("/api/issues")));
            assertWorst("CRITICAL", issues);
            assertTrue(issues.contains("\"kind\":\"SHUTDOWN_INCOMPLETE\",\"severity\":\"CRITICAL\""), issues);
            assertTrue(issues.contains("\"label\":\"Group slowstop\",\"value\":\"state CLOSING, outstanding requests 1\""), issues);
            assertTrue(issues.contains("\"action\":\"retry-stop\",\"label\":\"Retry stop\",\"enabled\":false"), issues);
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
            // Mongoose still lists the failed close as an ordinary finished capture (proposal A2)
            String list = body(g.record("audit.list", s.command("audit.list")));
            assertTrue(list.contains("selector=cap/rec") && !list.contains("CLOSE_FAILED"), list);
            g.record("audit.status", s.command("audit.status"));
            // M1 (proposal §4.4): the failed close is an issue by resource id; both refusals are recorded
            String issues = body(g.record("GET /api/issues", s.get("/api/issues")));
            assertWorst("DEGRADED", issues);
            assertTrue(issues.contains("\"id\":\"CAPTURE_CLOSE_FAILED:cap:1\",\"kind\":\"CAPTURE_CLOSE_FAILED\",\"severity\":\"DEGRADED\",\"subject\":\"cap resource 1\""), issues);
            assertTrue(issues.contains("\"value\":\"java.lang.IllegalStateException: forced capture close failure\""), issues);
            assertTrue(issues.contains("\"label\":\"Capture slots in use\",\"value\":\"1 (resources held 0)\""), issues);
            assertTrue(issues.contains("\"label\":\"Cleanup failure\",\"value\":\"physical close: java.lang.IllegalStateException: forced capture close failure\""), issues);
            assertFalse(issues.contains("CAPTURE_CLEANUP_FAILED"), "one issue for one failed close: " + issues);
            assertTrue(issues.contains("\"action\":\"retry-close\",\"label\":\"Retry close\",\"enabled\":false"), issues);
            // the command and the REST start of rec2 were both refused by the limit
            assertEquals(2, count(issues, "\"kind\":\"REQUEST_REFUSED\""), issues);
            assertTrue(issues.contains("A capture slot is held; see any capture close failure issue."), issues);
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

            // M1 (proposal §4.5): per-queue drop count and health verdict; the first loss is not live on 1.0.33
            String queues = body(g.record("GET /api/queues", s.get("/api/queues")));
            assertTrue(queues.contains("\"dropped\":" + dropped + ","), queues);
            assertTrue(queues.contains("\"health\":\"DEGRADED\""), queues);
            assertTrue(queues.contains("\"healthReason\":\"" + dropped + " item(s) dropped by queue /feed/feed/group/slow/subscriber/"), queues);
            assertFalse(queues.contains("firstLoss"), queues);
            g.record("control.group.status slow", s.command("control.group.status", "slow"));
            assertFalse(body(g.record("GET /api/commands", s.get("/api/commands"))).contains("health"),
                    "no admin command exposes the queue-drops DEGRADED health check");
            String health = body(g.record("GET /api/health", s.get("/api/health")));
            assertTrue(health.contains("\"component\":\"feed\",\"check\":\"queue-drops:/feed/feed/group/slow/subscriber/"), health);
            assertTrue(health.contains("\"verdict\":\"DEGRADED\""), health);
            String issues = body(g.record("GET /api/issues", s.get("/api/issues")));
            assertWorst("DEGRADED", issues);
            assertTrue(issues.contains("\"kind\":\"QUEUE_LOSS\",\"severity\":\"DEGRADED\",\"subject\":\"feed → slow\""), issues);
            assertTrue(issues.contains("\"dropped\":" + dropped + ","), issues);
            assertTrue(issues.contains("The first loss (sequence and time of the QueueDrop record) is only in the group's control-audit"), issues);
            g.verify();
        }
    }

    @Test
    void e2_queueOverloadWithCountersDisabled() throws Exception {
        // Structured overload state remains observable with performance counters disabled.
        try (ScenarioServer s = new ScenarioServer(1_500, false)) {
            Scenarios.overload(s);
            String issues = body(s.get("/api/issues"));
            assertFalse(issues.contains("QUEUE_LOSS"), issues);
            assertWorst("DEGRADED", issues);
            assertTrue(issues.contains("\"failureKind\":\"OVERLOAD_LOSS\""), issues);
            assertTrue(issues.contains("\"overloadState\":\"DEGRADED\""), issues);
            assertTrue(issues.contains("\"fact\":\"Performance counters disabled\""), issues);
            assertTrue(issues.contains("Counter totals and service up/down gauges cannot be observed; structured overload and control health remain available.\",\"needs\":\"performanceMonitoring.enabled: true\",\"live\":true"), issues);
            assertTrue(issues.contains("\"liveGaps\":1"), issues);
            assertFalse(issues.contains("HEALTH_UNKNOWN"), "counters-disabled checks are folded into the gap: " + issues);
        }
    }

    @Test
    void f_refusedControlRequests() throws Exception {
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Golden g = new Golden("f-refusals", s);
            s.add("busy", "consumer", Fixtures.Healthy::new);
            awaitTrue("busy running", () -> s.server.controlGroupStatus("busy").state() == ControlDriver.GroupState.RUNNING, 5_000);
            s.server.stopProcessor("busy", "consumer", 2_000);
            // Mongoose1.1.0 refuses unknown names with an explicit state kind.
            assertTrue(body(g.record("server.processors.stop nosuch/x", s.command("server.processors.stop", "nosuch/x")))
                    .contains("stop REFUSED (REFUSED_STATE): no such group"));
            // Missing arguments are typed invalid refusals, not a transport exception.
            assertTrue(body(g.record("server.processors.stop (no args)", s.command("server.processors.stop")))
                    .contains("stop REFUSED (REFUSED_INVALID): usage: server.processors.stop <group>/<processor>"));
            // M1 (proposal §4.6): the console says what it can: nothing holds that name; the request was invalid
            String issues = body(g.record("GET /api/issues", s.get("/api/issues")));
            assertWorst("INFO", issues);
            assertTrue(issues.contains("\"kind\":\"REQUEST_REFUSED\",\"severity\":\"INFO\",\"subject\":\"nosuch/x\""), issues);
            assertTrue(issues.contains("\"failureKind\":\"REFUSED_STATE\""), issues);
            assertTrue(issues.contains("\"failureKind\":\"REFUSED_INVALID\""), issues);
            g.verify();
        }
        try (ScenarioServer s = new ScenarioServer(1_500)) {
            Golden g = new Golden("f-mailbox-full", s);
            var busy = Scenarios.fullMailbox(s);
            String refused = body(g.record("server.processors.stop busy/consumer (mailbox full)",
                    s.command("server.processors.stop", "busy/consumer")));
            // The1.1.0 command preserves REFUSED_ADMISSION and names the full mailbox.
            assertTrue(refused.contains("stop REFUSED (REFUSED_ADMISSION): group busy: processor-stop request refused: mailbox full (capacity 4096) for 100 ms"), refused);
            String issues = body(g.record("GET /api/issues", s.get("/api/issues")));
            assertWorst("INFO", issues);
            assertTrue(issues.contains("\"kind\":\"REQUEST_REFUSED\",\"severity\":\"INFO\",\"subject\":\"busy/consumer\""), issues);
            assertTrue(issues.contains("Refusal kind: REFUSED_ADMISSION"), issues);
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
            // a clean server has no issue at all, and still lists what cannot be observed
            String issues = body(g.record("GET /api/issues", s.get("/api/issues")));
            assertWorst(null, issues);
            assertTrue(issues.contains("\"issues\":[]") && issues.contains("\"liveGaps\":0"), issues);
            assertTrue(s.server.replayers().isEmpty());
            g.verify();
        }
    }

    private static String body(HttpResponse<String> r) {
        return r.body();
    }

    /** The exact worst severity of the issue list ("null" for none). */
    private static void assertWorst(String expected, String issues) {
        String want = expected == null ? "\"worst\":null" : "\"worst\":\"" + expected + "\"";
        assertTrue(issues.startsWith("{" + want + ","), "expected " + want + " in " + issues);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }
}
