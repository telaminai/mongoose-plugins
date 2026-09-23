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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two behaviours review found unpinned: {@code close()} waiting for the reader, and the reset of the
 * consecutive-failure counter. Both were reverted against the suite and both left it green.
 *
 * <p>They are together here because they are the same kind of gap — not a value a test forgot to assert,
 * but a behaviour with no test that could tell whether it happened.
 */
class AuditTailLifecycleTest {

    private static Path queueWith(int records) throws IOException {
        Path dir = Files.createTempDirectory("audit-lifecycle");
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExcerptAppender a = q.createAppender();
            for (int i = 0; i < records; i++) {
                try (DocumentContext dc = a.writingDocument()) {
                    dc.wire().getValueOut().text("eventLogRecord:\n  logTime: " + (1000 + i)
                            + "\n  event: Tick" + i + "\n");
                }
            }
        }
        return dir;
    }

    /**
     * F2, as the service runs it: a reader is inside the queue when {@code close()} is called, and must
     * be allowed to finish.
     *
     * <p>The reader ignores its interrupt, exactly as a Chronicle read in flight does — {@code
     * readingDocument} is not interruptible, so {@code shutdownNow} alone returns with the thread still
     * inside the queue. Shorten the await to zero and this fails with {@code ClosedIllegalStateException}
     * from the reader, which is the production symptom: a close under an active read, landing in the
     * tick's own catch where it was invisible.
     */
    @Test
    void closeWaitsForAReaderThatIsInsideTheQueue() throws Exception {
        Path dir = queueWith(200);
        ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build();
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        WebAdminService.AuditTailState state = new WebAdminService.AuditTailState(q, exec);

        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        CountDownLatch reading = new CountDownLatch(1);
        exec.execute(() -> {
            try {
                ExcerptTailer tailer = q.createTailer().toStart();
                long until = System.currentTimeMillis() + 120;
                while (System.currentTimeMillis() < until) {
                    try (DocumentContext dc = tailer.readingDocument()) {
                        if (dc.isPresent()) dc.wire().getValueIn().text();
                        else tailer.toStart();
                    }
                    reading.countDown();          // we are demonstrably inside the queue
                }
            } catch (Throwable t) {
                readerFailure.set(t);
                reading.countDown();
            }
        });

        assertTrue(reading.await(5, TimeUnit.SECONDS), "the reader never started");
        state.close();                             // must not return until the reader has stopped

        assertTrue(exec.isTerminated(), "close() returned with the reader still running");
        assertNull(readerFailure.get(),
                () -> "the queue was closed under an active read: " + readerFailure.get());
    }

    /**
     * The reset. Twenty failures scattered over a socket's life, each separated by a tick that worked,
     * must never close it — a healthy client would lose its connection, and a reconnect starts at the
     * live end, so it would lose records with it.
     *
     * <p>Delete {@code succeeded()}'s assignment and this fails on the twentieth failure.
     */
    @Test
    void failuesSeparatedByASuccessNeverGiveUpOnTheSocket() {
        WebAdminService.AuditTailState state =
                new WebAdminService.AuditTailState(null, Executors.newSingleThreadScheduledExecutor());
        long now = 0;
        for (int i = 0; i < WebAdminService.MAX_CONSECUTIVE_FAILURES * 5; i++) {
            now += WebAdminService.POLL_INTERVAL_MS;
            assertFalse(state.failed(now), "an isolated failure at tick " + i + " must not close the socket");
            now += WebAdminService.POLL_INTERVAL_MS;
            state.succeeded();
            assertEquals(0, state.consecutiveFailures, "a tick that worked clears the run");
        }
    }

    /**
     * The fuse has two conditions, and review asked for the second. The count alone is half a second at a
     * 25 ms poll, which a briefly blocked client reaches; the socket is given up on only once the run has
     * also lasted {@link WebAdminService#MIN_FAILURE_WINDOW_MS}.
     */
    @Test
    void anUninterruptedRunGivesUpOnlyOnceItHasLasted() {
        WebAdminService.AuditTailState state =
                new WebAdminService.AuditTailState(null, Executors.newSingleThreadScheduledExecutor());
        long now = 10_000;
        for (int i = 1; i <= WebAdminService.MAX_CONSECUTIVE_FAILURES * 2; i++) {
            assertFalse(state.failed(now + (long) i * WebAdminService.POLL_INTERVAL_MS),
                    "failure " + i + " is within the window: too early to give up");
        }
        assertTrue(state.consecutiveFailures >= WebAdminService.MAX_CONSECUTIVE_FAILURES);

        // the run began one poll after `now`, so the window closes one poll after that
        assertFalse(state.failed(now + WebAdminService.MIN_FAILURE_WINDOW_MS),
                "the window is measured from the FIRST failure, not from before it");
        assertTrue(state.failed(now + WebAdminService.MIN_FAILURE_WINDOW_MS
                        + WebAdminService.POLL_INTERVAL_MS),
                "a run that reaches the count AND lasts the window closes the socket");

        // and a success in the middle of a long run resets the clock as well as the count
        state.succeeded();
        assertEquals(0, state.firstFailureAt, "the run's start time must clear with its count");
        assertFalse(state.failed(now + 60_000), "the next failure starts a new run, however late it is");
    }
}
