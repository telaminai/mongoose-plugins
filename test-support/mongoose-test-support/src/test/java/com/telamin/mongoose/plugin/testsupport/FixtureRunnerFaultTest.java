/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FixtureRunnerFaultTest {

    @TempDir
    Path dir;

    @BeforeEach
    void reset() {
        FixtureFaultFlow.CALLS.clear();
        FixtureFaultFlow.failStop = false;
    }

    /** Records which classes it was asked for, delegating to its parent. */
    static final class RecordingLoader extends ClassLoader {
        final Set<String> asked = new HashSet<>();

        RecordingLoader() {
            super(FixtureRunnerFaultTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            asked.add(name);
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void class_names_use_the_class_loader_set_later() throws Exception {
        RecordingLoader loader = new RecordingLoader();
        FixtureRunner.builder()
                .processor(FixtureCounterFlow.class.getName())
                .mapper(FixtureUpperMapper.class.getName())
                .classLoader(loader)
                .sink("out")
                .cache("counterState", dir.resolve("c.json"))
                .feedLines(List.of("a"))
                .run();
        assertTrue(loader.asked.contains(FixtureCounterFlow.class.getName()));
        assertTrue(loader.asked.contains(FixtureUpperMapper.class.getName()), "mapper loaded through classLoader(...)");
    }

    @Test
    void a_failing_stop_still_tears_the_processor_down() {
        FixtureFaultFlow.failStop = true;
        Exception e = assertThrows(Exception.class, () -> FixtureRunner.builder()
                .processor(FixtureFaultFlow::new).sink("out").feedLines(List.of("a")).run());
        assertEquals("stop failed", e.getMessage());
        assertEquals(List.of("stop", "tearDown"), FixtureFaultFlow.CALLS);
    }

    @Test
    void a_failing_cache_tear_down_does_not_hide_the_original_failure() throws Exception {
        Path cache = dir.resolve("state.json");
        Exception e = assertThrows(Exception.class, () -> FixtureRunner.builder()
                .processor(FixtureFaultFlow::new).sink("out").cache("state", cache)
                .feedLines(List.of("POISON")).run());
        assertEquals("original failure", e.getMessage());
        assertTrue(List.of(e.getSuppressed()).stream().anyMatch(s -> String.valueOf(s).contains("cache write failed")
                        || String.valueOf(s.getCause()).contains("cache write failed")),
                "the cache's failure is attached as suppressed");
        assertEquals(List.of("stop", "tearDown"), FixtureFaultFlow.CALLS);
    }

    @Test
    void the_command_line_prints_the_stack_trace_to_its_console() throws Exception {
        Path feed = dir.resolve("boom.txt");
        Files.write(feed, List.of("ok", "BOOM"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int rc = FixtureRunner.run(new PrintStream(bytes, true, StandardCharsets.UTF_8), "--processor",
                FixtureCounterFlow.class.getName(), "--sink", "out", "--cache",
                "counterState=" + dir.resolve("s.json"), "--feed", feed.toString());
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, rc);
        assertTrue(out.contains("java.lang.IllegalStateException: bad event\n\tat "), out);
    }
}
