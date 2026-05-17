/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.svc.cache;

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
}
