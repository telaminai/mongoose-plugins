/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class MongooseTestHarnessTest {

    /**
     * Processor that echoes each String it receives onto the wired MessageSink.
     * Validates that the harness wires services for {@code @ServiceRegistered}.
     */
    public static class EchoHandler extends ObjectEventHandlerNode {
        private MessageSink<String> sink;

        @ServiceRegistered
        public void wire(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s && sink != null) {
                sink.accept("echo:" + s);
            }
            return true;
        }
    }

    @Test
    void boot_publish_assert_round_trip() {
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();
        InMemoryMessageSink captured = new InMemoryMessageSink();

        try (MongooseTestHarness h = MongooseTestHarness.builder()
                .feed("in", feed, "feed-agent")
                .sink("out", captured)
                .processor("processor-agent", "echo", new EchoHandler())
                .start()) {

            feed.offer("alpha");
            feed.offer("beta");

            h.awaitCondition(() -> captured.getMessages().size() >= 2);

            Assertions.assertEquals(List.of("echo:alpha", "echo:beta"), captured.getMessages());
        }
    }

    @Test
    void close_is_idempotent() {
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();
        InMemoryMessageSink captured = new InMemoryMessageSink();

        MongooseTestHarness h = MongooseTestHarness.builder()
                .feed("in", feed, "feed-agent")
                .sink("out", captured)
                .processor("processor-agent", "echo", new EchoHandler())
                .start();

        h.close();
        Assertions.assertDoesNotThrow(h::close);
    }

    @Test
    void await_condition_throws_on_timeout() {
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();
        try (MongooseTestHarness h = MongooseTestHarness.builder()
                .feed("in", feed, "feed-agent")
                .processor("processor-agent", "noop", (Object e) -> {})
                .start()) {

            Assertions.assertThrows(AssertionError.class,
                    () -> h.awaitCondition(() -> false, Duration.ofMillis(150)));
        }
    }
}
