/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Temporary files are unique per write; tearDown keeps a concurrent change dirty and never writes an uninitialised cache. */
class JsonFileCacheTempAndTearDownTest {

    @TempDir
    Path dir;

    private JsonFileCache open(Path f, boolean async) {
        JsonFileCache c = new JsonFileCache();
        c.setFileName(f.toString());
        c.setAsyncWrite(async);
        c.init();
        return c;
    }

    private List<String> names() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void a_write_does_not_use_a_fixed_temporary_path() throws Exception {
        Path f = dir.resolve("state.json");
        Files.createDirectories(dir.resolve("state.json.tmp"));   // a path another writer could hold: not ours to use
        JsonFileCache c = open(f, false);
        assertDoesNotThrow(() -> c.put("a", "alpha"));
        assertEquals("alpha", open(f, false).get("a"));
        assertTrue(Files.isDirectory(dir.resolve("state.json.tmp")), "left alone");
    }

    @Test
    void init_removes_only_this_files_stale_temporary_files() throws Exception {
        Path f = dir.resolve("state.json");
        Files.writeString(dir.resolve("state.json.12345.tmp"), "{\"partial");
        Files.writeString(dir.resolve("other.json.12345.tmp"), "{}");
        Files.writeString(dir.resolve("state.json.bak"), "{}");
        JsonFileCache c = open(f, false);
        c.put("a", 1);
        assertEquals(List.of("other.json.12345.tmp", "state.json", "state.json.bak"), names());
    }

    @Test
    void two_instances_writing_the_same_file_do_not_collide_on_a_temporary_file() throws Exception {
        Path f = dir.resolve("shared.json");
        JsonFileCache one = open(f, false);
        JsonFileCache two = open(f, false);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier b = new CyclicBarrier(2);
            List<Future<?>> runs = new ArrayList<>();
            for (JsonFileCache c : List.of(one, two)) {
                runs.add(ex.submit(() -> {
                    b.await();
                    for (int i = 0; i < 200; i++) c.put("k" + i, "x".repeat(2000));
                    return null;
                }));
            }
            for (Future<?> r : runs) {
                r.get();   // throws if a write failed, e.g. the other instance moved "our" temporary file away
            }
        } finally {
            ex.shutdownNow();
        }
        assertEquals(200, open(f, false).keys().size(), "the file is whole");
    }

    /** A cache value that, while the file is being written, makes another change (as another thread could). */
    public static class ChangesDuringWrite extends JsonFileCache.TypedData {
        static JsonFileCache target;

        public String getSideEffect() {
            if (target != null) {
                JsonFileCache t = target;
                target = null;
                t.put("late", "arrived during tearDown");
            }
            return "";
        }
    }

    @Test
    void a_change_made_during_tear_down_stays_dirty() throws Exception {
        Path f = dir.resolve("async.json");
        JsonFileCache c = open(f, true);
        c.put("a", 1);
        c.getCacheMap().put("hook", new ChangesDuringWrite());
        ChangesDuringWrite.target = c;
        c.tearDown();
        assertTrue(c.getUpdated().get(), "the change made while tearDown wrote must not be marked written");
        c.getCacheMap().remove("hook");
        c.doWork();
        assertEquals("arrived during tearDown", open(f, false).get("late"));
    }

    @Test
    void tear_down_after_a_failed_init_leaves_the_file_alone() throws Exception {
        Path f = dir.resolve("corrupt.json");
        Files.writeString(f, "{ not json");
        JsonFileCache c = new JsonFileCache();
        c.setFileName(f.toString());
        assertThrows(Exception.class, c::init);
        c.tearDown();
        assertEquals("{ not json", Files.readString(f), "an uninitialised cache must not overwrite the file with nothing");
    }

    @Test
    void a_failed_tear_down_keeps_the_cache_dirty() throws Exception {
        Path f = dir.resolve("fail.json");
        JsonFileCache c = open(f, true);
        c.put("a", 1);
        c.getCacheMap().put("failing", new JsonFileCacheAtomicWriteTest.FailingTypedData());
        assertThrows(Exception.class, c::tearDown);
        assertTrue(c.getUpdated().get());
        c.getCacheMap().remove("failing");
        c.doWork();
        assertEquals(Integer.valueOf(1), open(f, false).get("a"));
    }
}
