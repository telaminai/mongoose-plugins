/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.cache;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory cache. Bounded LRU when {@code maxSize > 0}; otherwise an unbounded
 * {@link ConcurrentHashMap}. Evictions are silently dropped — they don't fire
 * callbacks and the count is exposed via {@link #getEvictedCount()}.
 *
 * <p>Switch to bounded mode by setting {@code maxSize} BEFORE the first {@code put}.
 */
@Getter
public class InMemoryCache implements Cache {

    /**
     * Hard cap on entries. {@code 0} (default) means unbounded.
     * Must be set before first use — changes after that are not retroactive.
     */
    @Setter
    private int maxSize = 0;

    private Map<String, Object> cache;
    @Getter(AccessLevel.NONE)
    private final AtomicLong evictedCount = new AtomicLong();

    public long getEvictedCount() {
        return evictedCount.get();
    }

    public InMemoryCache() {
    }

    private Map<String, Object> backing() {
        if (cache != null) return cache;
        if (maxSize > 0) {
            cache = Collections.synchronizedMap(new LinkedHashMap<String, Object>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    if (size() > maxSize) {
                        evictedCount.incrementAndGet();
                        return true;
                    }
                    return false;
                }
            });
        } else {
            cache = new ConcurrentHashMap<>();
        }
        return cache;
    }

    @Override
    public Collection<String> keys() {
        return backing().keySet();
    }

    @Override
    public void put(String key, Object value) {
        backing().put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) backing().get(key);
    }

    @Override
    public void remove(String key) {
        backing().remove(key);
    }

    public int size() {
        return backing().size();
    }
}
