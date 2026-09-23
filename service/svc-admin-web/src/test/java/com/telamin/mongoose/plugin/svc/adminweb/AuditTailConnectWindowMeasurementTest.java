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
 * How WIDE is the connect window, in milliseconds? Not "is there one" — the probe answers that.
 *
 * <p>Each round writes one record every ~200µs from the instant the client sees {@code onOpen}, each
 * tagged with the microseconds elapsed since then. The first record the client is delivered therefore
 * names the width of the window directly: everything with a smaller tag was written after the socket
 * was open and was still not delivered.
 *
 * <p>This exists because the previous round of this work asserted a number instead of measuring one, and
 * was wrong by orders of magnitude. It prints a distribution rather than asserting a bound, so it cannot
 * become the same kind of claim.
 */
class AuditTailConnectWindowMeasurementTest {

    private static final int ROUNDS = 10;
    private static final long WRITE_FOR_MICROS = 60_000;      // 60ms of writing per round
    private static final String PROCESSOR = "measure-processor";

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
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

    private static Set<Long> tags(String body) {
        Set<Long> out = new LinkedHashSet<>();
        String key = "micros";
        for (int i = body.indexOf(key); i >= 0; i = body.indexOf(key, i + 1)) {
            int j = i + key.length();
            while (j < body.length() && (body.charAt(j) == ':' || body.charAt(j) == ' '
                    || body.charAt(j) == '"' || body.charAt(j) == '\\')) j++;
            int start = j;
            while (j < body.length() && Character.isDigit(body.charAt(j))) j++;
            if (j > start) out.add(Long.parseLong(body.substring(start, j)));
        }
        return out;
    }

    @Test
    void measureTheWindow() throws Exception {
        List<Long> widths = new ArrayList<>();

        for (int round = 0; round < ROUNDS; round++) {
            Path dir = Files.createTempDirectory("measure-" + round);

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
                        // nothing bound
                    }
                }
            }
            assertNotNull(svc, "could not bind for round " + round);

            try (ChronicleQueue writer = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
                ExcerptAppender appender = writer.createAppender();
                Set<Long> delivered = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
                CountDownLatch open = new CountDownLatch(1);

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
                                            delivered.addAll(tags(buf.toString()));
                                            buf.setLength(0);
                                        }
                                        w.request(1);
                                        return null;
                                    }
                                })
                        .get(10, TimeUnit.SECONDS);

                assertTrue(open.await(10, TimeUnit.SECONDS), "round " + round + ": never opened");
                long t0 = System.nanoTime();

                long elapsed;
                do {
                    elapsed = (System.nanoTime() - t0) / 1000;
                    try (DocumentContext dc = appender.writingDocument()) {
                        dc.wire().getValueOut().text("eventLogRecord:\n  logTime: " + (1_000_000 + elapsed)
                                + "\n  event: Tick\n  nodeLogs:\n    - probe: { micros: " + elapsed + "}\n");
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(200_000);
                } while (elapsed < WRITE_FOR_MICROS);

                int stable = 0, last = -1;
                for (int i = 0; i < 60 && stable < 4; i++) {
                    Thread.sleep(50);
                    int now = delivered.size();
                    stable = (now == last) ? stable + 1 : 0;
                    last = now;
                }
                ws.abort();

                long firstDelivered = delivered.stream().mapToLong(Long::longValue).min().orElse(-1);
                widths.add(firstDelivered);
                System.out.println("[WINDOW] round " + round + ": first delivered record was written "
                        + firstDelivered + "µs after onOpen; delivered " + delivered.size());
            } finally {
                svc.stop();
            }
        }

        List<Long> sorted = widths.stream().sorted().toList();
        System.out.println("[WINDOW] widths µs (sorted): " + sorted);
        System.out.println("[WINDOW] median " + sorted.get(sorted.size() / 2)
                + "µs, max " + sorted.get(sorted.size() - 1) + "µs");
    }
}
