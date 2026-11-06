/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache.fakedelivery;

/** Test double with the shape of Mongoose's DeliveryContext: a static current() for the calling thread. */
public final class FakeDeliveryContext {

    private static final ThreadLocal<FakeDelivery> CURRENT = new ThreadLocal<>();

    private FakeDeliveryContext() {
    }

    public static FakeDelivery current() {
        return CURRENT.get();
    }

    public static void enter(String feed, long sequence, long epoch, Object position) {
        CURRENT.set(new FakeDelivery(feed, sequence, epoch, position));
    }

    public static void exit() {
        CURRENT.remove();
    }
}
