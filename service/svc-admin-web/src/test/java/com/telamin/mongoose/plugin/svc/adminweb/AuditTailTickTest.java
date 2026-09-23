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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The audit-tail poll, driven as the service actually runs it.
 *
 * <p><b>Why this class exists.</b> {@code AuditTailThreadingTest} proves the PATTERN is sound: it drives
 * a bare Chronicle queue and re-implements the tick loop inside the test. Review showed what that leaves
 * open — both defects the fix was written for could be reverted in the production code and the whole
 * module suite stayed green, because no test ran the service's own loop. Every assertion here runs
 * {@link WebAdminService#tick}, so reverting either defect fails.
 *
 * <p>The clock is a parameter rather than a sleep, so the latency rule is testable without timing.
 */
class AuditTailTickTest {

    private static Path queueWith(String... payloads) throws IOException {
        Path dir = Files.createTempDirectory("audit-tick");
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExcerptAppender a = q.createAppender();
            for (String p : payloads) {
                try (DocumentContext dc = a.writingDocument()) {
                    dc.wire().getValueOut().text(p);
                }
            }
        }
        return dir;
    }

    /** One audit record, in the shape the projection expects. */
    private static String record(int n) {
        return "eventLogRecord:\n  logTime: " + (1000 + n) + "\n  event: Tick" + n + "\n";
    }

    private static String[] records(int count) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) out[i] = record(i);
        return out;
    }

    private static WebAdminService.AuditTailState stateFor(ChronicleQueue q) {
        return new WebAdminService.AuditTailState(q, Executors.newSingleThreadScheduledExecutor());
    }

    /**
     * The defect that was invisible: a tick read records into a LOCAL list and discarded them unless the
     * flush condition was met, with the tailer already advanced past them.
     */
    @Test
    void recordsReadByOneTickAreSentByALater() throws IOException {
        Path dir = queueWith(records(3));                       // far below the batch threshold
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            var state = stateFor(q);
            ExcerptTailer tailer = q.createTailer().toStart();
            List<Object> sent = new ArrayList<>();
            long t0 = state.lastFlush;

            assertFalse(WebAdminService.tick(state, tailer, sent::add, t0),
                    "three records, inside the latency window: nothing is due yet");
            assertEquals(3, state.pending.size(), "but they must be KEPT — the tailer has passed them");
            assertTrue(sent.isEmpty());

            // the next tick is past the latency window and finds nothing new
            assertTrue(WebAdminService.tick(state, tailer, sent::add, t0 + 500),
                    "the records an earlier tick read must still go out");
            assertEquals(1, sent.size());
            assertTrue(sent.get(0).toString().contains("Tick0"), sent.get(0).toString());
            assertTrue(sent.get(0).toString().contains("Tick2"), sent.get(0).toString());
            assertTrue(state.pending.isEmpty(), "and only what was sent is forgotten");
        }
    }

    /** A full batch goes immediately, without waiting for the latency window. */
    @Test
    void aFullBatchIsSentAtOnce() throws IOException {
        Path dir = queueWith(records(WebAdminService.BATCH_THRESHOLD));
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            var state = stateFor(q);
            List<Object> sent = new ArrayList<>();
            assertTrue(WebAdminService.tick(state, q.createTailer().toStart(), sent::add, state.lastFlush),
                    "a full batch does not wait for the clock");
            assertEquals(1, sent.size());
            assertTrue(state.pending.isEmpty());
        }
    }

    /**
     * A send that fails keeps the records and does not advance the flush clock, so the next tick retries
     * rather than dropping them or stampeding.
     */
    @Test
    void aFailedSendKeepsTheRecordsAndRetries() throws IOException {
        Path dir = queueWith(records(3));
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            var state = stateFor(q);
            ExcerptTailer tailer = q.createTailer().toStart();
            long t0 = state.lastFlush;
            long before = state.lastFlush;

            assertThrows(IllegalStateException.class, () -> WebAdminService.tick(state, tailer, s -> {
                throw new IllegalStateException("client gone");
            }, t0 + 500));

            assertEquals(3, state.pending.size(), "a failed send must not lose what it was sending");
            assertEquals(before, state.lastFlush, "nor advance the clock, or the retry would be delayed");

            List<Object> sent = new ArrayList<>();
            assertTrue(WebAdminService.tick(state, tailer, sent::add, t0 + 600), "and the retry sends it");
            assertEquals(1, sent.size());
            assertTrue(state.pending.isEmpty());
        }
    }

    /**
     * The ceiling the surviving batch needs (F4). Without it, a client that never drains grows the batch
     * for ever — silently, one debug line per tick — which is worse than the drop it replaced.
     */
    @Test
    void aClientThatNeverDrainsIsBoundedRatherThanGrowingForEver() throws IOException {
        Path dir = queueWith(records(WebAdminService.MAX_PENDING + 500));
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            var state = stateFor(q);
            ExcerptTailer tailer = q.createTailer().toStart();
            long t = state.lastFlush;
            // once the batch is at the threshold each tick reads exactly one more, which is where the
            // reviewer's "about 40 records a second" comes from at a 25 ms poll. Reaching the ceiling
            // therefore takes roughly MAX_PENDING ticks, i.e. about four minutes in production.
            for (int i = 0; i < WebAdminService.MAX_PENDING + 100
                    && state.pending.size() < WebAdminService.MAX_PENDING; i++) {
                try {
                    WebAdminService.tick(state, tailer, s -> {
                        throw new IllegalStateException("client never drains");
                    }, t += 100);
                } catch (IllegalStateException expected) {
                    // the send fails every time, exactly as a stuck client behaves
                }
            }
            assertEquals(WebAdminService.MAX_PENDING, state.pending.size(),
                    "the batch must stop growing at the ceiling");
            // and one more tick reads nothing further, so the caller can see the client is behind
            int atCeiling = state.pending.size();
            final long last = t + 100;
            assertThrows(IllegalStateException.class,
                    () -> WebAdminService.tick(state, tailer, s -> {
                        throw new IllegalStateException("still stuck");
                    }, last));
            assertEquals(atCeiling, state.pending.size(), "and stays there");
        }
    }

    /**
     * The wire format, pinned at the signature rather than at the bytes.
     *
     * <p>Review read the bytecode and found {@code send} declared {@code Consumer<Object>}, so the call
     * site {@code ctx::send} bound to Javalin's {@code send(Object)} and every frame went through the
     * JSON mapper. It is byte-identical today only because JavalinJackson passes a String through
     * untouched; configure a mapper without that and every frame becomes a quoted, escaped string.
     *
     * <p>{@code Consumer<String>} makes the compiler choose {@code send(String)}, so there is nothing
     * left to assert about the bytes — which is exactly why this checks the declaration instead. No
     * behavioural test can see the difference while the passthrough exists, and the day it stops
     * existing is the day it would be found in production.
     */
    @Test
    void theSendCallbackIsDeclaredOnStringsSoTheFrameIsNotReSerialised() throws Exception {
        var tick = WebAdminService.class.getDeclaredMethod("tick",
                WebAdminService.AuditTailState.class,
                ExcerptTailer.class,
                java.util.function.Consumer.class,
                long.class);
        var param = (java.lang.reflect.ParameterizedType) tick.getGenericParameterTypes()[2];
        assertEquals(String.class, param.getActualTypeArguments()[0],
                "tick must take a Consumer<String>: as Consumer<Object> the handler's ctx::send binds to "
                        + "send(Object) and the wire format depends on the mapper's String passthrough");
    }

    /**
     * The tailer must be created on the thread that reads it, which is what {@code state.tailer()} does
     * lazily. This drives the state's own accessor, not a re-implementation of it.
     *
     * <p>It starts at the LIVE END, so the records written before it exists are deliberately not seen —
     * a tail shows what happens next. Note the consequence, which is worth knowing and is not a defect:
     * a client that drops and reconnects misses everything written while it was away.
     */
    @Test
    void theStateCreatesItsTailerOnTheReadingThread() throws Exception {
        Path dir = queueWith(records(2));                     // written BEFORE the tailer exists
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            var state = stateFor(q);
            var exec = Executors.newSingleThreadExecutor();
            try {
                List<Object> sent = new ArrayList<>();
                java.util.concurrent.Callable<Boolean> pollOnce = () -> {
                    try {
                        return WebAdminService.tick(state, state.tailer(), sent::add,
                                System.currentTimeMillis() + 500);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };

                assertFalse(exec.submit(pollOnce).get(),
                        "the tail starts at the live end: earlier records are not replayed");

                try (ChronicleQueue w = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
                    ExcerptAppender a = w.createAppender();
                    try (DocumentContext dc = a.writingDocument()) {
                        dc.wire().getValueOut().text(record(99));
                    }
                }

                assertTrue(exec.submit(pollOnce).get(),
                        "a tailer built and read on ONE thread delivers what arrives after it");
                assertEquals(1, sent.size());
                assertTrue(sent.get(0).toString().contains("Tick99"), sent.get(0).toString());
            } finally {
                exec.shutdownNow();
            }
        }
    }
}
