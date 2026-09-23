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
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The connect window, measured rather than argued.
 *
 * <p>Round 4 of review rejected the claim that the window between the queue opening and the tail being
 * positioned is "microseconds". It is not. From the client's side the window spans the WHOLE connect
 * handler: {@code onOpen} fires on the HTTP 101 upgrade while the server is still building a Chronicle
 * queue, creating a thread and submitting the positioning task. A queue build is milliseconds.
 *
 * <p>Production is the bad case, not the mild one: the audit sink already holds its queue open, so its
 * writes are as fast as this probe's, which keeps a queue handle open across the connect.
 *
 * <p>This probe is what the acceptance could not see. {@code AuditTailDeliveryAcceptanceTest} opens a
 * FRESH queue inside its {@code append()}, and that build is slower than the server's positioning, so it
 * passes even when the window is wide open. That is a property of the harness, not of the service.
 *
 * <p>It asserts on ids rather than counts, so a duplicate and a loss cannot cancel out.
 */
class AuditTailConnectWindowProbeTest {

    private static final int ROUNDS = 20;
    private static final int PER_ROUND = 40;
    private static final String PROCESSOR = "probe-processor";

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String record(int seq) {
        return "eventLogRecord:\n  logTime: " + (1_000_000 + seq) + "\n  event: Tick\n"
                + "  nodeLogs:\n    - probe: { seq: " + seq + "}\n";
    }

    private static MongooseAuditIntrospectionService introspection(Path dir) {
        AuditSinkHandle handle = new AuditSinkHandle(PROCESSOR, PROCESSOR, dir, 0, 0, 0,
                Instant.now(), Instant.now(), true);
        return new MongooseAuditIntrospectionService() {
            @Override public List<AuditSinkHandle> listAvailable() { return List.of(handle); }
            @Override public AuditSinkHandle currentSink(String p) {
                return PROCESSOR.equals(p) ? handle : null;
            }
            @Override public Map<String, AuditSinkHandle> currentSinks() { return Map.of(PROCESSOR, handle); }
        };
    }

    /** Every {@code seq: N} in the text, as a set — so a duplicate and a loss cannot cancel out. */
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

    @Test
    void everyRoundDeliversWhatItsExportContains() throws Exception {
        int lostRounds = 0;
        List<String> detail = new ArrayList<>();

        for (int round = 0; round < ROUNDS; round++) {
            Path dir = Files.createTempDirectory("probe-" + round);

            // freePort() hands back a port that is free at the instant it is asked, which another
            // process can take before Jetty binds. Retry rather than fail a measurement run on it.
            WebAdminService svc = null;
            int port = -1;
            for (int attempt = 0; attempt < 10 && svc == null; attempt++) {
                int candidate = freePort();
                WebAdminService s2 = new WebAdminService();
                s2.setListenPort(candidate);
                s2.setHost("127.0.0.1");
                s2.setAuthMode(WebAdminService.AuthMode.NONE);
                s2.auditIntrospectionService(introspection(dir), "audit");
                s2.init();
                try {
                    s2.start();
                    svc = s2;
                    port = candidate;
                } catch (Exception bindRace) {
                    try {
                        s2.stop();
                    } catch (Exception ignored) {
                        // nothing bound; nothing to release
                    }
                }
            }
            assertNotNull(svc, "could not bind a port for round " + round);

            // The queue handle is ALREADY OPEN before the client connects, exactly as the audit sink's
            // is in production. This is the difference between this probe and the acceptance.
            try (ChronicleQueue writer = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
                ExcerptAppender appender = writer.createAppender();

                Set<Integer> delivered = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
                CountDownLatch open = new CountDownLatch(1);
                final int base = round * 1000;

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
                                            delivered.addAll(seqs(buf.toString()));
                                            buf.setLength(0);
                                        }
                                        w.request(1);
                                        return null;
                                    }
                                })
                        .get(10, TimeUnit.SECONDS);

                assertTrue(open.await(10, TimeUnit.SECONDS), "round " + round + ": socket never opened");

                // No pause whatsoever. This is the window.
                for (int i = 0; i < PER_ROUND; i++) {
                    try (DocumentContext dc = appender.writingDocument()) {
                        dc.wire().getValueOut().text(record(base + i));
                    }
                }

                // settle
                int stable = 0, last = -1;
                for (int i = 0; i < 60 && stable < 4; i++) {
                    Thread.sleep(50);
                    int now = delivered.size();
                    stable = (now == last) ? stable + 1 : 0;
                    last = now;
                }
                ws.abort();

                HttpResponse<String> exported = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/api/audit/file/" + PROCESSOR + "/export?format=yaml")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                Set<Integer> inExport = seqs(exported.body());

                Set<Integer> missing = new LinkedHashSet<>(inExport);
                missing.removeAll(delivered);
                Set<Integer> extra = new LinkedHashSet<>(delivered);
                extra.removeAll(inExport);

                if (!missing.isEmpty() || !extra.isEmpty()) {
                    lostRounds++;
                    detail.add("round " + round + ": delivered " + delivered.size()
                            + " of " + inExport.size() + ", missing " + missing.size()
                            + (extra.isEmpty() ? "" : ", extra " + extra.size()));
                }
            } finally {
                svc.stop();
            }
        }

        System.out.println("[PROBE] rounds losing records: " + lostRounds + " of " + ROUNDS);
        detail.forEach(d -> System.out.println("[PROBE] " + d));

        assertEquals(0, lostRounds,
                "records written immediately after connect were lost in " + lostRounds + " of " + ROUNDS
                        + " rounds — the connect window is not closed. " + detail);
    }
}
