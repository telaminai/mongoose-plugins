/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A fixture runs the host's lifecycle: init, services, start, startComplete, then the feed lines, stop, tearDown. */
class FixtureRunnerLifecycleTest {

    @TempDir
    Path dir;

    /** Records its lifecycle; refuses events before startComplete, publishes at startComplete, can fail there. */
    public static class LifecycleFlow implements DataFlow {
        static final List<String> CALLS = new ArrayList<>();
        static boolean failStartComplete;
        private MessageSink<Object> sink;
        private boolean ready;

        @Override
        @SuppressWarnings("unchecked")
        public void registerService(Service<?> service) {
            if (service.instance() instanceof MessageSink<?> s) {
                sink = (MessageSink<Object>) s;
            }
        }

        @Override
        public void init() {
            CALLS.add("init");
        }

        @Override
        public void start() {
            CALLS.add("start");
        }

        @Override
        public void startComplete() {
            CALLS.add("startComplete");
            if (failStartComplete) {
                throw new IllegalStateException("startComplete failed");
            }
            ready = true;
            sink.accept("ready");
        }

        @Override
        public void onEvent(Object event) {
            if (event instanceof String s) {
                if (!ready) {
                    throw new IllegalStateException("startup not complete");
                }
                CALLS.add("event " + s);
                sink.accept("seen " + s);
            }
        }

        @Override
        public void stop() {
            CALLS.add("stop");
        }

        @Override
        public void tearDown() {
            CALLS.add("tearDown");
        }

        @Override
        public <T> T getNodeById(String id) throws NoSuchFieldException {
            throw new NoSuchFieldException(id);
        }
    }

    @BeforeEach
    void reset() {
        LifecycleFlow.CALLS.clear();
        LifecycleFlow.failStartComplete = false;
    }

    @Test
    void start_completes_before_the_first_feed_line() throws Exception {
        FixtureRunner.Result r = FixtureRunner.builder().processor(LifecycleFlow::new).sink("out")
                .feedLines(List.of("a", "b")).run();
        assertEquals(List.of("init", "start", "startComplete", "event a", "event b", "stop", "tearDown"),
                LifecycleFlow.CALLS);
        assertEquals(List.of("ready", "seen a", "seen b"), r.sinkLines(), "output published at startComplete is collected");
    }

    @Test
    void each_restart_completes_its_start() throws Exception {
        FixtureRunner.Result r = FixtureRunner.builder().processor(LifecycleFlow::new).sink("out")
                .feedLines(List.of("a")).restartLines(List.of("b")).run();
        assertEquals(List.of("ready", "seen a", "ready", "seen b"), r.sinkLines());
    }

    @Test
    void a_failing_start_complete_fails_the_run_and_still_stops_and_tears_down() throws Exception {
        LifecycleFlow.failStartComplete = true;
        Path cache = dir.resolve("state.json");
        Exception e = assertThrows(Exception.class, () -> FixtureRunner.builder().processor(LifecycleFlow::new)
                .sink("out").cache("state", cache).feedLines(List.of("a")).run());
        assertEquals("startComplete failed", e.getMessage());
        assertEquals(List.of("init", "start", "startComplete", "stop", "tearDown"), LifecycleFlow.CALLS,
                "no feed line after a failed start; cleanup as for any failure");
        assertTrue(Files.size(cache) > 0, "the cache was still torn down (written)");
    }
}
