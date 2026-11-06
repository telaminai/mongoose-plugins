/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache.fakedelivery;

import java.util.ArrayDeque;

/**
 * Test double with the shape of Mongoose 1.1.3's DeliveryContext including the delivery-complete hook: static
 * {@code current()}, {@code onComplete(Runnable)} and {@code attempt()}. The test plays the reader: {@link #complete()}
 * runs the callbacks, {@link #dispatchFailed()} discards them and counts the failed attempt, {@link #exit()} discards.
 */
public final class FakeHookDeliveryContext {

    private static final ThreadLocal<FakeDelivery> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<Runnable>> PENDING = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<int[]> ATTEMPT = ThreadLocal.withInitial(() -> new int[1]);

    private FakeHookDeliveryContext() {
    }

    public static FakeDelivery current() {
        return CURRENT.get();
    }

    public static boolean onComplete(Runnable callback) {
        if (CURRENT.get() == null) return false;
        for (Runnable pending : PENDING.get()) {
            if (pending == callback) return true;
        }
        PENDING.get().add(callback);
        return true;
    }

    public static int attempt() {
        return CURRENT.get() == null ? 0 : ATTEMPT.get()[0];
    }

    public static void enter(String feed, long sequence, long epoch, Object position) {
        CURRENT.set(new FakeDelivery(feed, sequence, epoch, position));
        PENDING.get().clear();
        ATTEMPT.get()[0] = 0;
    }

    /**
     * The reader: the dispatch succeeded; run the callbacks in order. A callback that throws stays first, so calling
     * this again retries it, as the reader does.
     */
    public static void complete() {
        Runnable next;
        while ((next = PENDING.get().peek()) != null) {
            next.run();
            PENDING.get().poll();
        }
    }

    /** The reader: the dispatch attempt failed; discard its callbacks and count it. */
    public static void dispatchFailed() {
        PENDING.get().clear();
        ATTEMPT.get()[0]++;
    }

    public static int pending() {
        return PENDING.get().size();
    }

    public static void exit() {
        CURRENT.remove();
        PENDING.get().clear();
        ATTEMPT.get()[0] = 0;
    }
}
