/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.svc.cache;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InMemoryCacheTest {

    @Test
    void put_get_keys_remove_round_trip() {
        Cache cache = new InMemoryCache();

        cache.put("alpha", 1);
        cache.put("beta", "two");

        Assertions.assertEquals(1, (Integer) cache.get("alpha"));
        Assertions.assertEquals("two", cache.get("beta"));
        Assertions.assertTrue(cache.keys().contains("alpha"));
        Assertions.assertTrue(cache.keys().contains("beta"));

        cache.remove("alpha");
        Assertions.assertNull(cache.get("alpha"));
        Assertions.assertFalse(cache.keys().contains("alpha"));
    }

    @Test
    void get_or_default_returns_fallback_for_missing_key() {
        Cache cache = new InMemoryCache();
        cache.put("present", 42);

        Assertions.assertEquals(42, (Integer) cache.getOrDefault("present", 0));
        Assertions.assertEquals(99, (Integer) cache.getOrDefault("absent", 99));
    }

    @Test
    void unbounded_by_default() {
        InMemoryCache cache = new InMemoryCache();
        for (int i = 0; i < 1000; i++) cache.put("k" + i, i);

        Assertions.assertEquals(1000, cache.size());
        Assertions.assertEquals(0, cache.getEvictedCount());
    }

    @Test
    void bounded_lru_evicts_eldest_on_overflow() {
        InMemoryCache cache = new InMemoryCache();
        cache.setMaxSize(3);

        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        // Touch 'a' so it becomes the most-recently-used.
        Assertions.assertEquals(1, (Integer) cache.get("a"));
        // Inserting 'd' must evict 'b' (the eldest by access order).
        cache.put("d", 4);

        Assertions.assertEquals(3, cache.size());
        Assertions.assertNull(cache.get("b"), "b should have been evicted");
        Assertions.assertEquals(1, (Integer) cache.get("a"));
        Assertions.assertEquals(3, (Integer) cache.get("c"));
        Assertions.assertEquals(4, (Integer) cache.get("d"));
        Assertions.assertEquals(1, cache.getEvictedCount());
    }

    @Test
    void bounded_cache_eviction_count_increments_per_overflow() {
        InMemoryCache cache = new InMemoryCache();
        cache.setMaxSize(2);

        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3); // evicts a
        cache.put("d", 4); // evicts b
        cache.put("e", 5); // evicts c

        Assertions.assertEquals(2, cache.size());
        Assertions.assertEquals(3, cache.getEvictedCount());
    }
}
