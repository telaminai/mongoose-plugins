/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** JsonFileCache writes through a sibling temporary file moved into place: never in place, compact, retry on failure. */
class JsonFileCacheAtomicWriteTest {

    @TempDir
    Path tempDir;

    /** A cache value whose serialisation fails part way through the file, as a write interrupted mid-way would. */
    public static class FailingTypedData extends JsonFileCache.TypedData {
        public String getFailure() {
            throw new IllegalStateException("simulated failure mid-write");
        }
    }

    private JsonFileCache open(Path file) {
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(file.toString());
        cache.init();
        return cache;
    }

    /** This file's temporary files, {@code <name>.<random>.tmp}. */
    private static java.util.List<Path> temps(Path file) throws java.io.IOException {
        String prefix = file.getFileName() + ".";
        try (var s = Files.list(file.getParent())) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    @Test
    void writes_compact_json_and_leaves_no_temporary_file() throws Exception {
        Path file = tempDir.resolve("state.json");
        JsonFileCache cache = open(file);
        cache.put("a", "alpha");
        cache.put("b", 42);

        String json = Files.readString(file);
        assertFalse(json.contains("\n"), "compact JSON, one line: " + json);
        assertFalse(json.contains(": "), "compact JSON, no pretty printing: " + json);
        assertEquals(java.util.List.of(), temps(file), "temporary file moved into place");
        assertEquals("alpha", open(file).get("a"));
        assertEquals(Integer.valueOf(42), open(file).get("b"));
    }

    @Test
    void a_pretty_printed_file_written_before_1_1_2_still_loads() throws Exception {
        Path file = tempDir.resolve("old.json");
        Files.writeString(file, """
                {
                  "name" : {
                    "type" : "java.lang.String",
                    "data" : "\\"alpha\\""
                  },
                  "count" : {
                    "type" : "java.lang.Integer",
                    "data" : "42"
                  }
                }""");
        JsonFileCache cache = open(file);
        assertEquals("alpha", cache.get("name"));
        assertEquals(Integer.valueOf(42), cache.get("count"));
        cache.put("more", "x");
        assertFalse(Files.readString(file).contains("\n"), "rewritten compact");
        assertEquals("alpha", open(file).get("name"));
    }

    @Test
    void a_write_failing_part_way_leaves_the_previous_file_intact() throws Exception {
        Path file = tempDir.resolve("state.json");
        JsonFileCache cache = open(file);
        cache.put("a", "alpha");
        cache.put("big", "x".repeat(100_000));   // enough that a partial write has bytes to lose
        byte[] before = Files.readAllBytes(file);

        cache.getCacheMap().put("failing", new FailingTypedData());
        assertThrows(RuntimeException.class, () -> cache.put("b", "beta"));

        assertArrayEquals(before, Files.readAllBytes(file), "previous file untouched by the failed write");
        assertEquals(java.util.List.of(), temps(file), "partial temporary file removed");
        JsonFileCache reloaded = open(file);
        assertEquals("alpha", reloaded.get("a"));
        assertNull(reloaded.get("b"));

        // still dirty: once the failure is gone the next write succeeds and carries the new key
        cache.getCacheMap().remove("failing");
        cache.doWork();
        assertEquals("beta", open(file).get("b"));
    }

    @Test
    void a_stale_temporary_file_from_a_crash_is_ignored_and_removed() throws Exception {
        Path file = tempDir.resolve("state.json");
        JsonFileCache first = open(file);
        first.put("a", "alpha");
        Files.writeString(file.resolveSibling("state.json.4711.tmp"), "{\"a\":{\"type\":\"java.lang.Str");   // a writer killed mid-write

        JsonFileCache restarted = open(file);
        assertEquals("alpha", restarted.get("a"));
        assertEquals(java.util.List.of(), temps(file));
        restarted.put("b", "beta");
        assertEquals("beta", open(file).get("b"));
    }

    @Test
    void async_write_defers_to_do_work_and_tear_down_writes_atomically() throws Exception {
        Path file = tempDir.resolve("async.json");
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(file.toString());
        cache.setAsyncWrite(true);
        cache.init();

        cache.put("a", "alpha");
        assertEquals(0, Files.size(file), "async: nothing written until doWork");
        cache.doWork();
        assertEquals("alpha", open(file).get("a"));

        cache.put("b", "beta");
        cache.tearDown();
        assertEquals("beta", open(file).get("b"));
        assertEquals(java.util.List.of(), temps(file));
    }

    @Test
    void bounded_cache_writes_the_evicted_state() throws Exception {
        Path file = tempDir.resolve("bounded.json");
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(file.toString());
        cache.setMaxSize(2);
        cache.init();
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        assertEquals(1, cache.getEvictedCount());
        JsonFileCache reloaded = open(file);
        assertNull(reloaded.get("a"));
        assertEquals("3", reloaded.get("c"));
    }
}
