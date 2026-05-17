/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: cache state must survive a process boundary.
 * Writes a populated cache, tearDown, fresh instance reads it back.
 * Verifies the JSON-on-disk persistence claim made in the catalogue.
 */
class JsonFileCacheSurvivalIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void cache_round_trips_through_disk() throws Exception {
        Path cacheFile = tempDir.resolve("state.json");

        // First "process": populate the cache.
        JsonFileCache c1 = new JsonFileCache();
        c1.setFileName(cacheFile.toString());
        c1.init();
        c1.put("ccy:USD", "United States Dollar");
        c1.put("ccy:GBP", "Pound Sterling");
        c1.put("ccy:EUR", "Euro");
        c1.tearDown();

        assertTrue(Files.exists(cacheFile), "tearDown should leave the JSON file on disk");
        long persistedSize = Files.size(cacheFile);
        assertTrue(persistedSize > 0, "persisted cache file should be non-empty");

        // Second "process": fresh instance, same file.
        JsonFileCache c2 = new JsonFileCache();
        c2.setFileName(cacheFile.toString());
        c2.init();

        assertEquals("United States Dollar", c2.get("ccy:USD"));
        assertEquals("Pound Sterling", c2.get("ccy:GBP"));
        assertEquals("Euro", c2.get("ccy:EUR"));
        assertEquals(3, c2.keys().size());
        c2.tearDown();
    }

    @Test
    void cache_survives_lru_eviction_across_restart() throws Exception {
        Path cacheFile = tempDir.resolve("bounded.json");

        // Populate with maxSize=2 — third put must evict the first.
        JsonFileCache c1 = new JsonFileCache();
        c1.setFileName(cacheFile.toString());
        c1.setMaxSize(2);
        c1.init();
        c1.put("a", "1");
        c1.put("b", "2");
        c1.put("c", "3");
        assertEquals(1, c1.getEvictedCount());
        c1.tearDown();

        // Fresh instance reads only the post-eviction state.
        JsonFileCache c2 = new JsonFileCache();
        c2.setFileName(cacheFile.toString());
        c2.setMaxSize(2);
        c2.init();
        assertNull(c2.get("a"), "evicted entry must not resurface after restart");
        assertEquals("2", c2.get("b"));
        assertEquals("3", c2.get("c"));
        c2.tearDown();
    }

    @Test
    void empty_cache_survives_an_empty_round_trip() throws IOException {
        Path cacheFile = tempDir.resolve("empty.json");

        JsonFileCache c1 = new JsonFileCache();
        c1.setFileName(cacheFile.toString());
        c1.init();
        c1.tearDown();

        JsonFileCache c2 = new JsonFileCache();
        c2.setFileName(cacheFile.toString());
        c2.init();
        assertEquals(0, c2.keys().size());
        c2.tearDown();
    }
}
