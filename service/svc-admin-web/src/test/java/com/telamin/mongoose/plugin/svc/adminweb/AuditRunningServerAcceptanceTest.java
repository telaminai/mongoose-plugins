/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The acceptance driven against a REAL Mongoose server, rather than against {@link WebAdminService}
 * standing on its own.
 *
 * <p><b>How this differs from {@link AuditTailDeliveryAcceptanceTest}, and why both exist.</b> That one
 * constructs the service directly and hands it a stub {@code MongooseAuditIntrospectionService} over a
 * Chronicle queue this test writes itself. It proves the socket's own behaviour and it is fast. What it
 * cannot prove is that anything upstream of the service works: that a booted server creates the sink,
 * that a processor's audit records reach the queue, that the service finds the sink by name.
 *
 * <p>This one boots {@link MongooseServer} with {@code PerformanceMonitoringConfig.auditCapture}
 * enabled, so the core builds the real {@code ChronicleAuditCaptureService} and
 * {@code DirAuditIntrospectionService} and injects them; a real event feed drives a real processor whose
 * audit records are written by the core's own listener. Nothing about the audit path is stubbed.
 *
 * <p><b>What is harness and what is shipped.</b> The event source, the handler, the records written into
 * the sink and the websocket client are harness. The audit sink, the introspection service, the export
 * endpoint, the socket and everything they touch are shipped code.
 *
 * <p><b>Why the records are written by the harness rather than by the processor.</b> They have to be, on
 * THIS path. Driving real events through a processor built from a {@code customHandler} with
 * {@code auditLog.info(...)} at level DEBUG produces <b>zero</b> audit records: the DataFlow Mongoose
 * builds for that path contains no {@code EventLogManager} auditor, so
 * {@code getAuditorById("eventLogger")} throws {@code NoSuchFieldException} and the logger has nothing
 * to publish through. The {@code POST /api/processors/{group}/{name}/audit/level} endpoint returns 200
 * and changes nothing.
 *
 * <p><b>This is NOT a claim about Mongoose processors in general, and an earlier version of this comment
 * wrongly read as one.</b> Review scoped it correctly: the analyser's preserved real export fixture
 * {@code c21-real-export.yaml} holds 25 records of which 7 carry node entries — verified by counting
 * them — so an AOT-built processor does log through Mongoose. What is broken is the
 * DataFlow-for-{@code customHandler} path specifically.
 *
 * <p>The stream-end marker is harness for a second, separate reason: <b>the shipped marker writer does
 * not exist yet</b>. This says what the exporter does with a marker, not that Mongoose produces one.
 */
class AuditRunningServerAcceptanceTest {

    private static final String PROCESSOR = "audit-acceptance";
    private static final String GROUP = "audit-acceptance-agent";

    private MongooseServer server;
    private Path auditDir;
    private int port;
    private InMemoryEventSource<String> feed;

    /**
     * Harness. It calls {@code auditLog.info} on every event, which produces nothing — see the class
     * comment. It is kept because a real processor on a real agent thread is part of the shape being
     * tested, and because the day the {@code EventLogManager} gap is fixed this starts producing records
     * and {@link #theProcessorsAuditLogProducesNothing()} fails, which is the notification we want.
     */
    public static class AuditingHandler extends ObjectEventHandlerNode {
        static final AtomicInteger SEEN = new AtomicInteger();
        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String) {
                SEEN.incrementAndGet();
                auditLog.info("tick", String.valueOf(event));
            }
            return true;
        }
    }

    /** Write records into the REAL sink the running server created. */
    private void writeToSink(ExcerptAppender appender, int from, int count) {
        for (int i = 0; i < count; i++) {
            try (DocumentContext dc = appender.writingDocument()) {
                dc.wire().getValueOut().text("eventLogRecord:\n  logTime: " + (1_000_000 + from + i)
                        + "\n  event: Tick\n  nodeLogs:\n    - probe: { seq: " + (from + i) + "}\n");
            }
        }
    }

    /** Every {@code seq: N}, as a set. */
    private static java.util.Set<Integer> seqs(String body) {
        java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
        String key = "seq";
        for (int i = body.indexOf(key); i >= 0; i = body.indexOf(key, i + 1)) {
            int j = i + key.length();
            while (j < body.length() && (body.charAt(j) == ':' || body.charAt(j) == ' '
                    || body.charAt(j) == '"' || body.charAt(j) == '\\')) j++;
            int start = j;
            while (j < body.length() && Character.isDigit(body.charAt(j))) j++;
            if (j > start) out.add(Integer.parseInt(body.substring(start, j)));
        }
        return out;
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void boot() throws Exception {
        auditDir = Files.createTempDirectory("audit-e2e");
        port = freePort();
        feed = new InMemoryEventSource<>();

        WebAdminService adminWeb = new WebAdminService();
        adminWeb.setHost("127.0.0.1");
        adminWeb.setListenPort(port);
        adminWeb.setAuthMode(WebAdminService.AuthMode.NONE);

        AuditCaptureConfig audit = new AuditCaptureConfig();
        audit.setEnabled(true);
        audit.setBackend("chronicle");
        audit.setDirectory(auditDir.toString());
        audit.setAutoStart(List.of(PROCESSOR));      // record from boot, so nothing is missed

        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setEnabled(true);
        perf.setAuditCapture(audit);

        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .addService(ServiceConfig.<WebAdminService>builder()
                        .service(adminWeb)
                        .serviceClass(WebAdminService.class)
                        .name("adminWebService")
                        .build())
                .addEventFeed(EventFeedConfig.builder()
                        .instance(feed)
                        .name("in")
                        // without broadcast a handler receives nothing unless it subscribes by name;
                        // omitting it was why the first run of this harness saw only lifecycle events
                        .broadcast(true)
                        .agent("feed-agent", new org.agrona.concurrent.BusySpinIdleStrategy())
                        .build())
                .addProcessor(GROUP, PROCESSOR, com.telamin.mongoose.config.EventProcessorConfig.builder()
                        .customHandler(new AuditingHandler())
                        .name(PROCESSOR)
                        .build())
                .build();
        cfg.setPerformanceMonitoring(perf);

        server = MongooseServer.bootServer(cfg);
        awaitHealthy();
        setAuditLevel("INFO");
    }

    /**
     * Shipped code: {@code POST /api/processors/{group}/{name}/audit/level}. Without it the processor's
     * audit logger is below INFO and the sink stays empty — which is the first thing this run found.
     */
    private void setAuditLevel(String level) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/api/processors/" + GROUP + "/"
                                + PROCESSOR + "/audit/level"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"level\":\"" + level + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("[DIAG] set audit level " + level + " -> " + r.statusCode() + " " + r.body());
    }

    private void awaitHealthy() throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        for (int i = 0; i < 100; i++) {
            try {
                HttpResponse<String> r = c.send(
                        HttpRequest.newBuilder(URI.create(base() + "/healthz")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return;
            } catch (Exception retry) {
                // the server is still coming up
            }
            Thread.sleep(50);
        }
        fail("the server never became healthy on " + base());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private String get(String path) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        return r.body();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
                // going away; must not mask an assertion
            }
        }
    }

    /** Documents in a YAML container: one per {@code eventLogRecord:} opener. */
    private static int countDocuments(String body) {
        int n = 0;
        for (int i = body.indexOf("eventLogRecord"); i >= 0; i = body.indexOf("eventLogRecord", i + 1)) n++;
        return n;
    }

    /** The sink directory the running server chose for our processor. */
    private Path sinkDir() throws Exception {
        String files = get("/api/audit/files");
        assertTrue(files.contains(PROCESSOR),
                () -> "the running server never registered a sink for " + PROCESSOR + ": " + files);
        try (var paths = Files.walk(auditDir)) {
            return paths.filter(Files::isDirectory)
                    .filter(p -> !p.equals(auditDir))
                    .findFirst()
                    .orElse(auditDir);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The finding this run produced
    // ------------------------------------------------------------------------------------------------

    /**
     * A processor built from a {@code customHandler}, on a real agent thread, calling
     * {@code auditLog.info} on every event at level DEBUG, produces NOTHING — because the graph
     * Mongoose builds for that path has no {@code EventLogManager} auditor to publish through.
     *
     * <p>Scoped deliberately: AOT-built processors DO log (see the class comment). This is about one
     * construction path.
     *
     * <p>It asserts the behaviour as it is, so the day it changes somebody is told, rather than
     * asserting it as if it were correct. It is not correct — it means Mongoose's own audit capture
     * records an empty log for this kind of processor, and the admin endpoint that sets the level
     * returns 200 while changing nothing.
     */
    @Test
    void aCustomHandlerProcessorsAuditLogProducesNothing() throws Exception {
        boot();

        boolean auditorPresent = true;
        try {
            server.registeredProcessors().values().iterator().next().iterator().next()
                    .eventProcessor()
                    .getAuditorById(com.telamin.fluxtion.runtime.audit.EventLogManager.NODE_NAME);
        } catch (Exception noAuditor) {
            auditorPresent = false;
        }

        for (int i = 0; i < 25; i++) feed.offer("evt-" + i);
        for (int i = 0; i < 100 && AuditingHandler.SEEN.get() < 25; i++) Thread.sleep(20);
        assertEquals(25, AuditingHandler.SEEN.get(), "the processor must actually have seen the events");
        Thread.sleep(300);

        String exported = get("/api/audit/file/" + PROCESSOR + "/export?format=yaml");

        System.out.println("[FINDING] EventLogManager present in the graph: " + auditorPresent);
        System.out.println("[FINDING] processor saw " + AuditingHandler.SEEN.get()
                + " events; export holds " + countDocuments(exported) + " records");

        assertFalse(auditorPresent,
                "an EventLogManager IS now in the graph for a customHandler processor — the gap is fixed, "
                        + "and this test plus the class comment and the brief should be updated to say so");
        assertEquals(0, countDocuments(exported),
                "records are now being captured — the gap is fixed; update this test");
    }

    // ------------------------------------------------------------------------------------------------
    // RUN 2 — delivered equals exported, against a booted server
    // ------------------------------------------------------------------------------------------------

    /**
     * The ids delivered over the socket equal the ids the export contains, for the same window, against
     * a server that booted its own audit sink and its own introspection service.
     *
     * <p>Enough records to cross BOTH flush paths, and the frame sizes show that both actually fired.
     */
    @Test
    void deliveredEqualsExportedForARunningServer() throws Exception {
        boot();
        Path dir = sinkDir();

        java.util.Set<Integer> delivered = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
        List<Integer> frameSizes = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch open = new CountDownLatch(1);
        StringBuilder errors = new StringBuilder();

        try (ChronicleQueue writer = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExcerptAppender appender = writer.createAppender();

            WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/audit-tail/" + PROCESSOR),
                            new WebSocket.Listener() {
                                private final StringBuilder buf = new StringBuilder();

                                @Override public void onOpen(WebSocket w) {
                                    open.countDown();
                                    w.request(1);
                                }

                                @Override public CompletionStage<?> onText(WebSocket w, CharSequence d,
                                                                            boolean last) {
                                    buf.append(d);
                                    if (last) {
                                        String frame = buf.toString();
                                        buf.setLength(0);
                                        if (frame.contains("\"err\"")) {
                                            errors.append(frame);
                                        } else {
                                            java.util.Set<Integer> in = seqs(frame);
                                            if (!in.isEmpty()) {
                                                frameSizes.add(in.size());
                                                delivered.addAll(in);
                                            }
                                        }
                                    }
                                    w.request(1);
                                    return null;
                                }

                                @Override public void onError(WebSocket w, Throwable e) {
                                    errors.append(e);
                                }
                            })
                    .get(10, TimeUnit.SECONDS);
            assertTrue(open.await(10, TimeUnit.SECONDS), "the socket never opened");

            // Bursts, so the batch threshold fires inside them and the latency timer between them.
            int produced = 0;
            for (int burst = 0; burst < 5; burst++) {
                writeToSink(appender, produced, 60);
                produced += 60;
                Thread.sleep(120);
            }

            int stable = 0, last = -1;
            for (int i = 0; i < 100 && stable < 5; i++) {
                Thread.sleep(50);
                int now = delivered.size();
                stable = (now == last) ? stable + 1 : 0;
                last = now;
            }
            ws.abort();

            assertEquals("", errors.toString(), "the socket reported an error");

            String exported = get("/api/audit/file/" + PROCESSOR + "/export?format=yaml");
            java.util.Set<Integer> exportedSeqs = seqs(exported);

            java.util.Set<Integer> missing = new java.util.LinkedHashSet<>(exportedSeqs);
            missing.removeAll(delivered);
            java.util.Set<Integer> extra = new java.util.LinkedHashSet<>(delivered);
            extra.removeAll(exportedSeqs);

            System.out.println("[RUN2] produced=" + produced + " delivered=" + delivered.size()
                    + " exported=" + exportedSeqs.size() + " frames=" + frameSizes.size()
                    + " sizes=" + frameSizes);

            assertEquals(produced, exportedSeqs.size(), "the export must hold everything written");
            assertEquals(java.util.Set.of(), missing, "records in the export that were never delivered");
            assertEquals(java.util.Set.of(), extra, "records delivered that the export does not contain");

            assertTrue(frameSizes.stream().anyMatch(n -> n == WebAdminService.BATCH_THRESHOLD),
                    () -> "no frame hit the batch threshold, so that flush path never ran: " + frameSizes);
            assertTrue(frameSizes.stream().anyMatch(n -> n < WebAdminService.BATCH_THRESHOLD),
                    () -> "no frame was a timed partial flush, so that path never ran: " + frameSizes);
        }
    }

    /*
     * NOT HERE, and deliberately: the MAX_PENDING ceiling driven through a live server.
     *
     * It was written and it failed, and the premise rather than the service was wrong. A JDK websocket
     * client that never calls request() applies flow control in its own listener, not on the wire; the
     * server's ctx.send kept succeeding, so the pending batch was cleared every tick and never grew.
     * Two thousand records did not come close to a ten-thousand-record ceiling that only fills when
     * sends FAIL.
     *
     * So the ceiling is covered by AuditTailTickTest, where a send that throws is the actual condition,
     * and NOT by any live-server test. Reaching it live needs a client that accepts a connection and
     * then stops reading the socket at the TCP level, which is a different harness. Recorded as not
     * done rather than replaced by an assertion that would pass without testing anything.
     */

    // ------------------------------------------------------------------------------------------------
    // RUN 1 — the format, end to end
    // ------------------------------------------------------------------------------------------------

    /**
     * UP-MON-01 against a running server: the exporter terminates its last document, so a stream-end
     * marker appended to the real sink comes out as a complete, closed claim.
     */
    @Test
    void markedExportReadsAsAWholeLog() throws Exception {
        boot();
        Path dir = sinkDir();

        int produced = 40;
        try (ChronicleQueue writer = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            writeToSink(writer.createAppender(), 0, produced);
        }

        String before = get("/api/audit/file/" + PROCESSOR + "/export?format=yaml");
        int recordCount = countDocuments(before);
        assertEquals(produced, recordCount, "the running server must export what was written to its sink");

        // Every export, marked or not, closes its last document (UP-MON-01).
        assertTrue(before.endsWith("\n---\n"),
                () -> "an unmarked export must still terminate:\n" + tail(before));

        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExcerptAppender a = q.createAppender();
            try (DocumentContext dc = a.writingDocument()) {
                // no logTime: a released reader would otherwise widen the file's time range
                dc.wire().getValueOut().text(
                        "eventLogRecord:\n  streamEnd: normal\n  streamEndRecords: " + recordCount + "\n");
            }
        }

        String after = get("/api/audit/file/" + PROCESSOR + "/export?format=yaml");
        assertTrue(after.contains("streamEnd: normal"), () -> "the marker is missing:\n" + tail(after));
        assertTrue(after.contains("streamEndRecords: " + recordCount), tail(after));
        assertTrue(after.endsWith("\n---\n"),
                () -> "the marker must be followed by its separator or the analyser ignores the claim:\n"
                        + tail(after));

        Path evidence = Files.createTempDirectory("audit-evidence").resolve("marked-export.yaml");
        Files.writeString(evidence, after);
        System.out.println("[RUN1] records=" + recordCount + ", marked export at " + evidence);
        System.out.println("[RUN1] tail:\n" + tail(after));
    }

    private static String tail(String s) {
        return s.substring(Math.max(0, s.length() - 200));
    }
}
