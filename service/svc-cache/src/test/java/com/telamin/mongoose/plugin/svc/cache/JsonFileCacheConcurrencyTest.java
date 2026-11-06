/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A put or remove must change the map before it marks the cache dirty, and a synchronous put must not return before
 * its own change is on disk, whatever another thread (an agent's doWork, another synchronous put) does meanwhile.
 */
class JsonFileCacheConcurrencyTest {

    @TempDir
    Path dir;

    /** Whatever a value's serialisation runs: the moment inside put between "dirty" and "in the map" before the fix. */
    static final ThreadLocal<Runnable> DURING_SERIALISE = new ThreadLocal<>();

    public static class Interleaving {
        public String name = "v";

        public String getName() {
            Runnable r = DURING_SERIALISE.get();
            if (r != null) {
                DURING_SERIALISE.remove();
                r.run();
            }
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private JsonFileCache open(Path f, boolean async) {
        JsonFileCache c = new JsonFileCache();
        c.setFileName(f.toString());
        c.setAsyncWrite(async);
        c.init();
        return c;
    }

    private JsonFileCache reload(Path f) {
        return open(f, false);
    }

    @Test
    void async_agent_write_during_a_put_does_not_lose_the_put() throws Exception {
        Path f = dir.resolve("async.json");
        JsonFileCache c = open(f, true);
        c.put("first", 1);
        DURING_SERIALISE.set(() -> {
            try {
                c.doWork();   // the agent thread's duty cycle lands inside put
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        c.put("late", new Interleaving());
        c.doWork();           // the next duty cycle must still see the cache dirty
        assertNotNull(reload(f).get("late"), "put left unsaved: the agent took the dirty flag before the map changed");
    }

    @Test
    void sync_put_returns_only_after_its_value_is_on_disk() throws Exception {
        Path f = dir.resolve("sync.json");
        JsonFileCache c = open(f, false);
        DURING_SERIALISE.set(() -> c.put("other", 1));   // another thread's synchronous put lands inside this one
        c.put("mine", new Interleaving());
        assertNotNull(reload(f).get("mine"), "put returned before its value was written");
        assertNotNull(reload(f).get("other"));
    }

    @Test
    void sync_remove_returns_only_after_it_is_on_disk() throws Exception {
        Path f = dir.resolve("remove.json");
        JsonFileCache c = open(f, false);
        c.put("gone", 1);
        c.remove("gone");
        assertNull(reload(f).get("gone"));
    }

    // ---- races between real threads (ported from review), many trials -----------------------------------------

    static final String BIG = "x".repeat(1_000_000);

    @Test
    void async_race_with_an_agent_thread_loses_nothing() throws Exception {
        int lost = 0, trials = 40;
        for (int t = 0; t < trials; t++) {
            Path f = dir.resolve("a" + t + ".json");
            JsonFileCache c = open(f, true);
            c.put("small", 1);
            AtomicBoolean run = new AtomicBoolean(true);
            AtomicReference<Exception> failed = new AtomicReference<>();
            Thread agent = new Thread(() -> {
                while (run.get()) {
                    try {
                        c.doWork();
                    } catch (Exception e) {
                        failed.set(e);
                        return;
                    }
                }
            });
            agent.start();
            Thread.sleep(1);
            c.put("big", BIG);
            run.set(false);
            agent.join();
            assertNull(failed.get());
            c.doWork();
            if (reload(f).get("big") == null) {
                lost++;
            }
        }
        assertEquals(0, lost, "async puts left unsaved in " + lost + "/" + trials + " trials");
    }

    @Test
    void sync_race_between_two_threads_every_put_is_on_disk_when_it_returns() throws Exception {
        int lost = 0, trials = 40;
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            for (int t = 0; t < trials; t++) {
                Path f = dir.resolve("s" + t + ".json");
                JsonFileCache c = open(f, false);
                CyclicBarrier b = new CyclicBarrier(2);
                Future<?> a = ex.submit(() -> {
                    b.await();
                    for (int i = 0; i < 100; i++) c.put("a" + i, i);
                    return null;
                });
                Future<Boolean> big = ex.submit(() -> {
                    b.await();
                    c.put("big", BIG);
                    return Files.size(f) > 0 && new ObjectMapper().readTree(f.toFile()).has("big");
                });
                if (!big.get()) {
                    lost++;
                }
                a.get();
            }
        } finally {
            ex.shutdownNow();
        }
        assertEquals(0, lost, "a synchronous put returned before its value was on disk in " + lost + "/" + trials);
    }
}
