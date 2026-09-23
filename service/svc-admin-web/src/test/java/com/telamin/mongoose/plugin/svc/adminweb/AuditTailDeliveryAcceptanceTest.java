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
import java.util.List;
import java.util.Map;
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
 */
class AuditTailDeliveryAcceptanceTest {

    private static final int RECORDS = 250;          // several batches at a threshold of 32
    private static final String PROCESSOR = "acceptance-processor";

    private WebAdminService svc;
    private Path queueDir;

    @AfterEach
    void stop() {
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
                + "  nodeLogs:\n    - seq: { n: " + n + "}\n";
    }

    private void append(int from, int count) {
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(queueDir.toFile()).build()) {
            ExcerptAppender a = q.createAppender();
            for (int i = 0; i < count; i++) {
                try (DocumentContext dc = a.writingDocument()) {
                    dc.wire().getValueOut().text(record(from + i));
                }
            }
        }
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
        // create the queue, and leave it EMPTY: the window starts here
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(queueDir.toFile()).build()) {
            assertNotNull(q);
        }

        int port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setAuthMode(WebAdminService.AuthMode.NONE);
        svc.auditIntrospectionService(introspection(), "audit");
        svc.init();
        svc.start();

        AtomicInteger delivered = new AtomicInteger();
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
                                        // each frame is a JSON array of records; count the records in it
                                        delivered.addAndGet(countRecords(frame));
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

        int exportedCount = countDocuments(exported.body());
        assertEquals(RECORDS, exportedCount, "the export must contain the window it was asked for");
        assertEquals(exportedCount, delivered.get(),
                "DELIVERED must equal EXPORTED for the same window — the acceptance this whole fix owed");

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
