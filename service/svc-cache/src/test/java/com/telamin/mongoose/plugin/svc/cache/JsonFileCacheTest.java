/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class JsonFileCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void init_creates_cache_file_in_missing_parent_dir() throws IOException {
        Path cacheFile = tempDir.resolve("missing").resolve("nested").resolve("cache.json");
        Assertions.assertFalse(Files.exists(cacheFile.getParent()), "precondition");

        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(cacheFile.toString());
        cache.init();

        Assertions.assertTrue(Files.exists(cacheFile),
                "cache file should be created even when parent dirs are missing");
    }

    @Test
    void init_with_bare_basename_does_not_throw() throws IOException {
        Path bareFile = Path.of("svc-cache-bare-basename.tmp");
        Files.deleteIfExists(bareFile);
        try {
            JsonFileCache cache = new JsonFileCache();
            cache.setFileName(bareFile.toString());
            Assertions.assertDoesNotThrow(cache::init);
        } finally {
            Files.deleteIfExists(bareFile);
        }
    }

    @Test
    void init_with_empty_filename_throws() {
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName("");
        Assertions.assertThrows(IllegalStateException.class, cache::init);
    }

    @Test
    void init_with_null_filename_throws() {
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(null);
        Assertions.assertThrows(IllegalStateException.class, cache::init);
    }

    @Test
    void init_with_negative_max_size_throws() {
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(tempDir.resolve("cache.json").toString());
        cache.setMaxSize(-1);
        Assertions.assertThrows(IllegalStateException.class, cache::init);
    }

    @Test
    void bounded_cache_evicts_eldest_on_overflow() throws IOException {
        Path cacheFile = tempDir.resolve("bounded.json");
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(cacheFile.toString());
        cache.setMaxSize(2);
        cache.setAsyncWrite(true); // skip per-put writes — we'll flush once at the end
        cache.init();

        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        Assertions.assertEquals(2, cache.keys().size());
        Assertions.assertNull(cache.get("a"));
        Assertions.assertEquals("2", cache.get("b"));
        Assertions.assertEquals("3", cache.get("c"));
        Assertions.assertEquals(1, cache.getEvictedCount());

        cache.tearDown();
    }

    @Test
    void tear_down_without_init_is_safe() {
        JsonFileCache cache = new JsonFileCache();
        Assertions.assertDoesNotThrow(cache::tearDown);
    }
}
