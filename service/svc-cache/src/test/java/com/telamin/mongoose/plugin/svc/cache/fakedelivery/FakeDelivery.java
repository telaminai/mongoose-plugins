/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache.fakedelivery;

/** Test double with the shape of Mongoose's Delivery record. */
public record FakeDelivery(String feed, long sequence, long epoch, Object position) {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean alreadyApplied(long storedEpoch, Object storedPosition) {
        if (storedPosition == null || position == null || storedEpoch != epoch) return false;
        if (position instanceof Number p && storedPosition instanceof Number s) {
            return p.longValue() <= s.longValue();
        }
        return ((Comparable) position).compareTo(storedPosition) <= 0;
    }
}
