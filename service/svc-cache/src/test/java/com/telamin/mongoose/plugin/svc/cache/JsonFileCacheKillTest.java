/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A writer process is killed (SIGKILL) at random points while it rewrites a large cache entry; every time, the file
 * left behind must load and hold one whole value. Written in place, a kill mid-write leaves truncated JSON.
 */
class JsonFileCacheKillTest {

    @TempDir
    Path tempDir;

    @Test
    void a_writer_killed_mid_write_leaves_a_complete_file() throws Exception {
        Path file = tempDir.resolve("killed.json");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Random random = new Random(42);
        long previous = -1;
        for (int round = 0; round < 6; round++) {
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    JsonFileCacheKillWriter.class.getName(), file.toString())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                String line;
                while ((line = out.readLine()) != null && !line.equals("writing")) {
                    // child start-up output
                }
                assertEquals("writing", line, "writer started and completed its first write");
                Thread.sleep(20 + random.nextInt(150));
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            } finally {
                child.destroyForcibly();
            }

            JsonFileCache reopened = new JsonFileCache();
            reopened.setFileName(file.toString());
            reopened.init();   // throws on a truncated file
            String blob = reopened.get("blob");
            assertNotNull(blob, "round " + round);
            long seq = Long.parseLong(blob.substring(0, blob.indexOf(':')));
            assertEquals(Long.toString(seq % 10).repeat(JsonFileCacheKillWriter.PAYLOAD),
                    blob.substring(blob.indexOf(':') + 1), "round " + round + ": a whole value");
            assertTrue(seq > previous, "round " + round + ": writes from this round are kept");
            previous = seq;
            assertTrue(Files.size(file) > JsonFileCacheKillWriter.PAYLOAD);
        }
    }
}
