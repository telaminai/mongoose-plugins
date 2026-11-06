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
        String stale = JsonFileCache.tempName(f, 0x12345L);
        assertEquals(".state.json.0000000000012345.jfc.tmp", stale);
        Files.writeString(dir.resolve(stale), "{\"partial");
        String othersStale = JsonFileCache.tempName(dir.resolve("other.json"), 0x12345L);
        Files.writeString(dir.resolve(othersStale), "{}");
        List<String> notOurs = List.of("state.json.12345.tmp", "state.json.archive.tmp", ".state.json.12345.jfc.tmp",
                ".state.json.0000000000012345.jfc.tmp.bak", ".state.json.x.0000000000012345.jfc.tmp",
                ".state.json.000000000001234G.jfc.tmp", "state.json.bak");
        for (String n : notOurs) {
            Files.writeString(dir.resolve(n), "{}");
        }
        JsonFileCache c = open(f, false);
        c.put("a", 1);
        List<String> expected = new ArrayList<>(notOurs);
        expected.addAll(List.of(othersStale, "state.json"));
        assertEquals(expected.stream().sorted().toList(), names(), "only this file's own temporary file is removed");
    }

    @Test
    void a_file_name_of_the_temporary_form_is_refused() {
        JsonFileCache c = new JsonFileCache();
        c.setFileName(dir.resolve(JsonFileCache.tempName(dir.resolve("state.json"), 1)).toString());
        IllegalStateException e = assertThrows(IllegalStateException.class, c::init);
        assertTrue(e.getMessage().contains("temporary file"), e.getMessage());
    }

    @Test
    void temporary_files_are_owner_only_where_posix_permissions_exist() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX permissions here");
        Path f = dir.resolve("perm.json");
        open(f, false).put("a", 1);
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(f)), "the moved temporary file keeps its owner-only permissions");
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

    @Test
    void init_keeps_a_distinct_cache_whose_file_name_extends_this_one() throws Exception {
        Path archive = dir.resolve("state.json.archive.tmp");   // another cache's data file, not a temporary file
        open(archive, false).put("balance", 123);
        open(dir.resolve("state.json"), false);
        assertTrue(Files.exists(archive), "another cache's saved data must not be deleted by this cache's init");
        assertEquals(Integer.valueOf(123), open(archive, false).get("balance"));
    }

    /** A cache value whose serialisation waits until released, holding its cache's write open. */
    public static class BlockedWrite extends JsonFileCache.TypedData {
        static java.util.concurrent.CountDownLatch entered;
        static java.util.concurrent.CountDownLatch release;

        @Override
        public String getData() {
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not released");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "\"value\"";
        }
    }

    @Test
    void init_keeps_the_live_temporary_file_of_a_distinct_cache() throws Exception {
        JsonFileCache backup = open(dir.resolve("state.json.backup.json"), true);
        backup.put("old", "saved");
        backup.doWork();
        BlockedWrite.entered = new java.util.concurrent.CountDownLatch(1);
        BlockedWrite.release = new java.util.concurrent.CountDownLatch(1);
        BlockedWrite hook = new BlockedWrite();
        hook.setType(String.class);
        backup.getCacheMap().put("hook", hook);
        backup.put("new", "pending");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<?> write = ex.submit(() -> {
                backup.doWork();
                return null;
            });
            assertTrue(BlockedWrite.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "write in progress");
            open(dir.resolve("state.json"), false);   // while the other cache's temporary file is open
            BlockedWrite.release.countDown();
            assertDoesNotThrow(() -> write.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            BlockedWrite.release.countDown();
            ex.shutdownNow();
        }
        assertEquals("pending", open(dir.resolve("state.json.backup.json"), false).get("new"));
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
