/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE acceptance: the count a live client is DELIVERED equals the count the export contains, for the
 * same window.
 *
 * <p>This is what has been owed since the audit-tail defect was first fixed, and what every previous
 * round correctly refused to claim: it needs a running server and a client, and no shipped client opens
 * this socket. So the test brings its own — {@link java.net.http.HttpClient} speaks WebSocket natively,
 * so no dependency is added to do it.
 *
 * <p><b>Why "the same window" means starting from an empty queue.</b> The tail begins at the live end,
 * so it can only deliver what is written after it connects, while the export contains the whole file.
 * Connecting to an EMPTY queue and writing afterwards is the one arrangement in which both cover exactly
 * the same records, which is what makes the two counts comparable at all.
 *
 * <p>The original defect would fail this at the first assertion: the socket completed a real upgrade,
 * reported healthy, and delivered nothing.
 *
 * <p><b>What this test does NOT guard, measured rather than assumed.</b> Removing the pre-upgrade
 * positioning leaves this green while {@code AuditTailConnectWindowProbeTest} goes to 19 of 20 rounds
 * losing records. The reason is structural and worth stating: starting from an EMPTY queue is what makes
 * delivered and exported cover the same window, and it is also the one case the connect window cannot
 * hurt — an empty queue has no end to skip past, so the tail starts at the beginning and everything
 * written afterwards is caught regardless of when the server got round to positioning. The probe is the
 * regression guard for the window; this is the guard for delivery.
 */
class AuditTailDeliveryAcceptanceTest {

    private static final int RECORDS = 250;          // several batches at a threshold of 32
    private static final String PROCESSOR = "acceptance-processor";

    private WebAdminService svc;
    private Path queueDir;
    /** Held open across the connect, exactly as the audit sink holds its own. See {@link #append}. */
    private ChronicleQueue writer;
    private ExcerptAppender appender;

    @AfterEach
    void stop() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
                // the test is finished; a close failure must not mask the assertion
            }
        }
        if (svc != null) {
            try {
                svc.stop();
            } catch (Exception ignored) {
                // the service is going away; a failure here must not mask the assertion
            }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String record(int n) {
        return "eventLogRecord:\n  logTime: " + (1_000_000 + n) + "\n  event: Tick\n"
                + "  nodeLogs:\n    - probe: { seq: " + n + "}\n";
    }

    /**
     * Writes through an ALREADY-OPEN queue handle.
     *
     * <p>It used to open a fresh queue per call. Review found that this is what made the test pass: a
     * queue build is milliseconds, which was slower than the server's positioning, so the connect window
     * never showed up here even when it was wide open. The audit sink holds its queue open, so its writes
     * are as fast as these — this is the shape production has.
     */
    private void append(int from, int count) {
        for (int i = 0; i < count; i++) {
            try (DocumentContext dc = appender.writingDocument()) {
                dc.wire().getValueOut().text(record(from + i));
            }
        }
    }

    /** Every {@code seq: N}, as a set: comparing counts lets a duplicate and a loss cancel out. */
    private static Set<Integer> seqs(String body) {
        Set<Integer> out = new LinkedHashSet<>();
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

    /** The smallest introspection service that names one live sink at our queue directory. */
    private MongooseAuditIntrospectionService introspection() {
        AuditSinkHandle handle = new AuditSinkHandle(PROCESSOR, PROCESSOR, queueDir, 0, 0, 0,
                Instant.now(), Instant.now(), true);
        return new MongooseAuditIntrospectionService() {
            @Override public List<AuditSinkHandle> listAvailable() { return List.of(handle); }
            @Override public AuditSinkHandle currentSink(String processorName) {
                return PROCESSOR.equals(processorName) ? handle : null;
            }
            @Override public Map<String, AuditSinkHandle> currentSinks() { return Map.of(PROCESSOR, handle); }
        };
    }

    @Test
    void everyRecordWrittenAfterAClientConnectsIsDeliveredToIt() throws Exception {
        queueDir = Files.createTempDirectory("audit-accept");
        // create the queue, and leave it EMPTY: the window starts here. The handle stays open, so the
        // writes below are as fast as the sink's rather than paying for a queue build each time.
        writer = SingleChronicleQueueBuilder.binary(queueDir.toFile()).build();
        appender = writer.createAppender();

        int port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setAuthMode(WebAdminService.AuthMode.NONE);
        svc.auditIntrospectionService(introspection(), "audit");
        svc.init();
        svc.start();

        AtomicInteger delivered = new AtomicInteger();
        Set<Integer> deliveredSeqs = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        CountDownLatch open = new CountDownLatch(1);
        CountDownLatch enough = new CountDownLatch(1);
        StringBuilder errors = new StringBuilder();

        HttpClient client = HttpClient.newHttpClient();
        var ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/audit-tail/" + PROCESSOR),
                        new java.net.http.WebSocket.Listener() {
                            private final StringBuilder buf = new StringBuilder();

                            @Override public void onOpen(java.net.http.WebSocket webSocket) {
                                open.countDown();
                                webSocket.request(1);
                            }

                            @Override public CompletionStage<?> onText(java.net.http.WebSocket webSocket,
                                                                       CharSequence data, boolean last) {
                                buf.append(data);
                                if (last) {
                                    String frame = buf.toString();
                                    buf.setLength(0);
                                    if (frame.contains("\"err\"")) {
                                        errors.append(frame);
                                    } else {
                                        // each frame is a JSON array of records; collect their ids
                                        deliveredSeqs.addAll(seqs(frame));
                                        delivered.set(deliveredSeqs.size());
                                        if (delivered.get() >= RECORDS) enough.countDown();
                                    }
                                }
                                webSocket.request(1);
                                return null;
                            }

                            @Override public void onError(java.net.http.WebSocket webSocket, Throwable e) {
                                errors.append(e);
                                enough.countDown();
                            }
                        })
                .get(10, TimeUnit.SECONDS);

        assertTrue(open.await(10, TimeUnit.SECONDS), "the socket never opened");

        // No pause. A client that connects and immediately triggers activity must receive it: this is
        // where the lazy tailer lost 250 of 250, because toEnd() had not run yet.
        append(0, RECORDS);

        assertTrue(enough.await(30, TimeUnit.SECONDS),
                () -> "delivered " + delivered.get() + " of " + RECORDS
                        + " — this is the original defect: connected, healthy, nothing delivered. "
                        + errors);
        assertEquals("", errors.toString(), "the socket reported an error");

        // what the export says the same window contains
        HttpResponse<String> exported = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/audit/file/" + PROCESSOR + "/export?format=yaml")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exported.statusCode(), exported.body());

        Set<Integer> exportedSeqs = seqs(exported.body());
        assertEquals(RECORDS, exportedSeqs.size(), "the export must contain the window it was asked for");

        // Compared as SETS, not counts. A duplicate and a loss cancel out in a count; review asked for
        // this and it is the difference between "the same number" and "the same records".
        Set<Integer> missing = new LinkedHashSet<>(exportedSeqs);
        missing.removeAll(deliveredSeqs);
        Set<Integer> extra = new LinkedHashSet<>(deliveredSeqs);
        extra.removeAll(exportedSeqs);
        assertEquals(Set.of(), missing, "records in the export that were never delivered");
        assertEquals(Set.of(), extra, "records delivered that the export does not contain");

        // and the export closes its last document, so a marker appended to it would be a claim (UP-MON-01)
        assertTrue(exported.body().endsWith("\n---\n"),
                () -> "the export did not terminate its last document:\n"
                        + exported.body().substring(Math.max(0, exported.body().length() - 120)));

        ws.abort();
    }

    /** Records in one delivered frame: the frame is a JSON array, and every record carries `logTime`. */
    private static int countRecords(String frame) {
        int n = 0;
        for (int i = frame.indexOf("logTime"); i >= 0; i = frame.indexOf("logTime", i + 1)) n++;
        return n;
    }

    /** Documents in a YAML container: one per `eventLogRecord:` opener. */
    private static int countDocuments(String body) {
        int n = 0;
        for (int i = body.indexOf("eventLogRecord"); i >= 0; i = body.indexOf("eventLogRecord", i + 1)) n++;
        return n;
    }
}
