/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import com.telamin.mongoose.service.health.MongooseHealthService;
import com.telamin.mongoose.service.introspection.AgentGroupSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin-local half of the issues model: requests this console sent, acknowledgement, the gap list. */
class FailureIssuesTest {

    private static final FailureIssues.Sources NOTHING_WIRED = new FailureIssues.Sources() {
        @Override public MongooseServer server() { return null; }
        @Override public Map<String, AgentGroupSnapshot> agentGroups() { return Map.of(); }
        @Override public MongooseHealthService health() { return null; }
        @Override public MongooseCountersService counters() { return null; }
        @Override public List<AuditSinkHandle> captures() { return List.of(); }
    };

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> issues(FailureIssues f) {
        return (List<Map<String, Object>>) f.report().get("issues");
    }

    @Test
    void anAppliedStopIsNotAnIssue() {
        FailureIssues f = new FailureIssues(NOTHING_WIRED);
        f.recordCommand("server.processors.stop", List.of("g/p"), List.of("stopping processor:g/p", "stop APPLIED: stopped"), List.of());
        assertTrue(issues(f).isEmpty());
        assertNull(f.report().get("worst"));
    }

    @Test
    void refusalsAreInfoIssuesWithTheReasonVerbatim() {
        FailureIssues f = new FailureIssues(NOTHING_WIRED);
        f.recordCommand("server.processors.stop", List.of("busy/consumer"),
                List.of("stopping processor:busy/consumer", "stop REFUSED: group busy: processor-stop queue stayed full (4096) for 100 ms"),
                List.of());
        Map<String, Object> issue = issues(f).get(0);
        assertEquals("REQUEST_REFUSED", issue.get("kind"));
        assertEquals("INFO", issue.get("severity"));
        assertEquals("REFUSED", issue.get("outcome"));
        assertEquals("busy/consumer", issue.get("subject"));
        assertTrue(((String) issue.get("what")).endsWith("group busy: processor-stop queue stayed full (4096) for 100 ms"));
        assertTrue(((String) issue.get("impact")).contains("not draining its control mailbox"));
        assertEquals("INFO", f.report().get("worst"));
    }

    @Test
    void anInvalidStopAndARefusedCaptureAreRecorded() {
        FailureIssues f = new FailureIssues(NOTHING_WIRED);
        f.recordCommand("server.processors.stop", List.of(), List.of(), List.of("exception: Index 1 out of bounds for length 1"));
        f.recordCommand("audit.start", List.of("rec2"), List.of(),
                List.of("audit: audit start for 'rec2' FAILED: open failed: physical capture resource limit reached"));
        f.recordCaptureRefusal("stop", "pricer", new IllegalArgumentException("processor 'pricer' is ambiguous"));
        List<Map<String, Object>> list = issues(f);
        assertEquals(3, list.size());
        assertEquals(List.of("INVALID", "INVALID", "REFUSED"),
                list.stream().map(i -> String.valueOf(i.get("outcome"))).sorted().toList());
        assertTrue(list.stream().anyMatch(i -> "rec2".equals(i.get("subject"))
                && ((String) i.get("impact")).contains("A capture slot is held")));
    }

    @Test
    void pendingWithoutAServerIsNotClaimedUnknown() {
        // without a typed server the console cannot tell whether the name is held: it must not claim it is unknown
        FailureIssues f = new FailureIssues(NOTHING_WIRED);
        f.recordCommand("server.processors.stop", List.of("g/p"), List.of("stopping processor:g/p", "stop pending (still applies): g/p"), List.of());
        assertEquals("PENDING", issues(f).get(0).get("outcome"));
    }

    @Test
    void acknowledgingNeverClosesAnIssue() {
        FailureIssues f = new FailureIssues(NOTHING_WIRED);
        f.recordCommand("server.processors.stop", List.of("g/p"), List.of("stop FAILED: processor quarantined"), List.of());
        String id = (String) issues(f).get(0).get("id");
        assertTrue(f.acknowledge(id));
        Map<String, Object> report = f.report();
        @SuppressWarnings("unchecked")
        Map<String, Object> issue = ((List<Map<String, Object>>) report.get("issues")).get(0);
        assertEquals(Boolean.TRUE, issue.get("acknowledged"));
        assertEquals("INFO", report.get("worst"), "an acknowledged issue still counts toward the worst severity");
        assertEquals(0, report.get("unacknowledged"));
        assertFalse(f.acknowledge("no-such-issue"));
    }

    @Test
    void onlyTheLastTwentyRequestsAreKept() {
        FailureIssues f = new FailureIssues(NOTHING_WIRED);
        for (int i = 0; i < 25; i++) {
            f.recordCommand("server.processors.stop", List.of("g/p" + i), List.of("stop REFUSED: mailbox full"), List.of());
        }
        List<Map<String, Object>> list = issues(f);
        assertEquals(FailureIssues.MAX_REQUESTS, list.size());
        assertTrue(list.stream().noneMatch(i -> "g/p0".equals(i.get("subject"))));
    }

    @Test
    void theGapsAreListedSoAnEmptyListIsNeverHealthy() {
        Map<String, Object> report = new FailureIssues(NOTHING_WIRED).report();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) report.get("notAvailable");
        assertEquals("Group control status", gaps.get(0).get("fact"), "without a MongooseServer the console says so first");
        assertTrue(gaps.stream().anyMatch(g -> "First loss per queue".equals(g.get("fact"))));
    }

    // ---------------------------------------------------------------- health and counters

    private static MongooseHealthService health(java.util.function.Consumer<MongooseHealthService.StatusVisitor> walk) {
        return (MongooseHealthService) java.lang.reflect.Proxy.newProxyInstance(FailureIssuesTest.class.getClassLoader(),
                new Class<?>[]{MongooseHealthService.class}, (proxy, m, args) -> {
                    if (m.getName().equals("forEachStatus")) {
                        walk.accept((MongooseHealthService.StatusVisitor) args[0]);
                        return null;
                    }
                    return m.getName().equals("toString") ? "stub-health" : null;
                });
    }

    private static MongooseCountersService counters(boolean operational) {
        return (MongooseCountersService) java.lang.reflect.Proxy.newProxyInstance(FailureIssuesTest.class.getClassLoader(),
                new Class<?>[]{MongooseCountersService.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "isOperational" -> operational;
                    case "toString" -> "stub-counters";
                    default -> null;
                });
    }

    private static FailureIssues.Sources sources(MongooseHealthService h, MongooseCountersService c) {
        return new FailureIssues.Sources() {
            @Override public MongooseServer server() { return null; }
            @Override public Map<String, AgentGroupSnapshot> agentGroups() { return Map.of(); }
            @Override public MongooseHealthService health() { return h; }
            @Override public MongooseCountersService counters() { return c; }
            @Override public List<AuditSinkHandle> captures() { return List.of(); }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> gaps(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("notAvailable");
    }

    @Test
    void countersDisabledIsALiveBlindSpotNotSilence() {
        var h = health(v -> {
            v.visit("feed", "queue-drops:/feed/feed/group/g/subscriber/x", com.telamin.mongoose.service.health.HealthStatus.unknown("counters disabled"));
            v.visit("feed", "up", com.telamin.mongoose.service.health.HealthStatus.unknown("counters disabled"));
        });
        Map<String, Object> report = new FailureIssues(sources(h, counters(false))).report();
        assertEquals(2, report.get("liveGaps"), "counters off (and no server in this stub)");
        Map<String, Object> gap = gaps(report).stream()
                .filter(g -> "Performance counters disabled".equals(g.get("fact"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, gap.get("live"));
        assertTrue(((List<?>) report.get("issues")).isEmpty(), "folded into the gap, not one INFO per check");
    }

    @Test
    void healthServiceNotBoundIsALiveBlindSpot() {
        Map<String, Object> report = new FailureIssues(NOTHING_WIRED).report();
        assertEquals(2, report.get("liveGaps"), "no server and no health service");
        assertTrue(gaps(report).stream().anyMatch(g -> "Health checks".equals(g.get("fact")) && Boolean.TRUE.equals(g.get("live"))));
    }

    @Test
    void anOtherUnknownCheckIsListedAsInfo() {
        var h = health(v -> v.visit("venue", "session", com.telamin.mongoose.service.health.HealthStatus.unknown("no heartbeat yet")));
        Map<String, Object> issue = issues(new FailureIssues(sources(h, counters(true)))).get(0);
        assertEquals("HEALTH_UNKNOWN", issue.get("kind"));
        assertEquals("INFO", issue.get("severity"));
        assertEquals("venue/session", issue.get("subject"));
    }

    @Test
    void aFailingCheckIsReportedAndTheRestAreStillRead() {
        var h = health(v -> {
            v.visit("bad", "check", null);
            v.visit("venue", "session", com.telamin.mongoose.service.health.HealthStatus.down("logged out"));
            throw new IllegalStateException("health walk broke");
        });
        List<Map<String, Object>> list = issues(new FailureIssues(sources(h, counters(true))));
        assertTrue(list.stream().anyMatch(i -> "HEALTH_DOWN".equals(i.get("kind"))), list.toString());
        assertTrue(list.stream().anyMatch(i -> "READ_FAILED".equals(i.get("kind"))
                && String.valueOf(i.get("what")).contains("health walk broke")), list.toString());
    }
}
