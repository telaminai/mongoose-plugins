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
}
