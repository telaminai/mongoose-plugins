/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.connector.chronicle;

import com.fluxtion.dataflow.serverplugin.testsupport.MongooseTestHarness;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end round-trip through a Chronicle Queue on disk:
 * in-memory feed → handler → {@link ChronicleMessageSink} → file on disk →
 * {@link ChronicleEventSource} tails it → handler → in-memory sink.
 *
 * <p>Two-server topology, single JVM. Proves the chronicle queue's
 * method-writer / method-reader pairing survives the embedded dispatcher.
 */
class ChronicleRoundTripIntegrationTest {

    @TempDir
    Path tempDir;

    /** Stage 1: writes each String input into the chronicle sink. */
    public static class WriterHandler extends ObjectEventHandlerNode {
        @SuppressWarnings("unchecked")
        private MessageSink<String> sink;

        @ServiceRegistered
        public void wire(MessageSink<?> sink, String name) {
            if ("chronicle-out".equals(name)) {
                this.sink = (MessageSink<String>) sink;
            }
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s && sink != null) {
                sink.accept(s);
            }
            return true;
        }
    }

    /** Stage 2: forwards each tailed String onto an in-memory capture sink. */
    public static class TailHandler extends ObjectEventHandlerNode {
        @SuppressWarnings("unchecked")
        private MessageSink<String> sink;

        @ServiceRegistered
        public void wire(MessageSink<?> sink, String name) {
            if ("captured".equals(name)) {
                this.sink = (MessageSink<String>) sink;
            }
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s && sink != null) {
                sink.accept("read:" + s);
            }
            return true;
        }
    }

    @Test
    void chronicle_queue_round_trip() {
        Path chroniclePath = tempDir.resolve("chronicle");
        InMemoryEventSource<String> producer = new InMemoryEventSource<>();
        InMemoryMessageSink captured = new InMemoryMessageSink();

        ChronicleMessageSink writer = new ChronicleMessageSink();
        writer.setChroniclePath(chroniclePath.toString());

        ChronicleEventSource reader = new ChronicleEventSource("chronicle-reader");
        reader.setChroniclePath(chroniclePath.toString());
        reader.setReadStrategy(ReadStrategy.EARLIEST);

        try (MongooseTestHarness h = MongooseTestHarness.builder()
                .feed("producer", producer, "producer-agent")
                .sink("chronicle-out", writer)
                .feed("chronicle-in", reader, "reader-agent")
                .sink("captured", captured)
                .processor("writer-agent", "writer", new WriterHandler())
                .processor("reader-processor", "reader", new TailHandler())
                .start()) {

            producer.offer("alpha");
            producer.offer("beta");
            producer.offer("gamma");

            h.awaitCondition(() -> captured.getMessages().size() >= 3);

            assertEquals(List.of("read:alpha", "read:beta", "read:gamma"),
                    captured.getMessages());
        }
    }
}
