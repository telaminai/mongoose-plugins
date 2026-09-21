/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two defects behind {@code /ws/audit-tail/{processor}} delivering nothing.
 *
 * <p>The socket reported a healthy connection and zero messages, including for records appended after
 * the client connected. Two causes, and the second was hidden by the first.
 *
 * <p><b>Why this test works on a bare Chronicle queue rather than a running server.</b> Both defects are
 * about which THREAD touches the tailer and what happens to records a tick has already consumed. Neither
 * needs Javalin, a session or HTTP, and a websocket test would prove the same thing far more slowly while
 * depending on ports and timing. What it does not cover is the end-to-end count, which is the acceptance
 * the proposal asks for and which needs a live server; that is recorded as still owed.
 */
class AuditTailThreadingTest {

    private static void write(Path dir, String... payloads) {
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExcerptAppender a = q.createAppender();
            for (String p : payloads) {
                try (DocumentContext dc = a.writingDocument()) {
                    dc.wire().getValueOut().text(p);
                }
            }
        }
    }

    /**
     * The first defect, reproduced. A tailer created on one thread and read on another throws; the
     * production code created it on the Jetty connect thread and read it on a scheduled executor, and
     * caught the throw at debug level, so the failure was invisible.
     */
    @Test
    void aTailerCreatedOnOneThreadAndReadOnAnotherFails() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("audit-tail-a");
        write(dir, "one", "two");

        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExcerptTailer created = q.createTailer().toEnd();   // "connect thread"
            ExecutorService other = Executors.newSingleThreadExecutor();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            other.submit(() -> {
                try (DocumentContext dc = created.readingDocument()) {
                    dc.isPresent();
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }).get(10, TimeUnit.SECONDS);
            other.shutdownNow();

            assertNotNull(thrown.get(),
                    "Chronicle must refuse a cross-thread tailer; if this ever stops throwing, the "
                            + "lazy-creation fix is no longer load-bearing and should be revisited");
            assertTrue(thrown.get().getClass().getSimpleName().contains("Threading"),
                    "expected a threading refusal, got " + thrown.get());
        }
    }

    /**
     * The fix: create the tailer on the thread that reads it. This is what {@code AuditTailState.tailer()}
     * now does, lazily, on first tick.
     */
    @Test
    void aTailerCreatedOnTheReadingThreadDeliversTheRecords() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("audit-tail-b");
        write(dir, "one", "two");

        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExecutorService reader = Executors.newSingleThreadExecutor();
            List<String> seen = reader.submit(() -> {
                ExcerptTailer t = q.createTailer().toStart();    // created HERE, used HERE
                List<String> out = new ArrayList<>();
                while (true) {
                    try (DocumentContext dc = t.readingDocument()) {
                        if (!dc.isPresent()) break;
                        out.add(dc.wire().getValueIn().text());
                    }
                }
                return out;
            }).get(10, TimeUnit.SECONDS);
            reader.shutdownNow();

            assertEquals(List.of("one", "two"), seen);
        }
    }

    /**
     * The second defect, which the first one hid. A tick read records into a LOCAL list and discarded it
     * unless the flush condition was met — fewer than the batch threshold, and within the latency window.
     * The tailer had already advanced, so those records were gone.
     *
     * <p>This models the loop rather than calling it, because the production method is a lambda inside a
     * websocket handler. The assertion is the one that matters: across two ticks, nothing read is lost.
     */
    @Test
    void recordsReadButNotFlushedSurviveUntilTheyAreSent() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("audit-tail-c");
        write(dir, "r1", "r2");                       // two records: below any sane batch threshold

        final int BATCH_THRESHOLD = 32;
        List<String> sent = new ArrayList<>();
        List<String> pending = new ArrayList<>();     // the fix: batch lives across ticks

        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            exec.submit(() -> {
                ExcerptTailer t = q.createTailer().toStart();
                for (int tick = 0; tick < 2; tick++) {
                    while (true) {
                        try (DocumentContext dc = t.readingDocument()) {
                            if (!dc.isPresent()) break;
                            pending.add(dc.wire().getValueIn().text());
                            if (pending.size() >= BATCH_THRESHOLD) break;
                        }
                    }
                    // tick 0: too few to flush and inside the latency window -> nothing sent.
                    // Before the fix `pending` was a local here and those records were dropped.
                    boolean flush = !pending.isEmpty() && (pending.size() >= BATCH_THRESHOLD || tick == 1);
                    if (flush) {
                        sent.addAll(pending);
                        pending.clear();
                    }
                }
            }).get(10, TimeUnit.SECONDS);
            exec.shutdownNow();
        }

        assertEquals(List.of("r1", "r2"), sent,
                "a record read by one tick must be sent by a later one, never dropped");
        assertTrue(pending.isEmpty(), "everything read was eventually sent");
    }
}
