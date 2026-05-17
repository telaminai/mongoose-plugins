/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.connector.file;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ReadStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end round-trip through a real {@link MongooseServer}:
 * input file → {@link FileEventSource} → handler → {@link FileMessageSink} → output file.
 * Proves the connector pair plus the dispatcher wire up correctly under the
 * documented Java-builder config style.
 */
class FileRoundTripIntegrationTest {

    @TempDir
    Path tempDir;

    /**
     * Processor: forwards every incoming String event to the registered MessageSink,
     * prefixed so the test can distinguish input-shape from output-shape and confirm
     * the dispatcher actually invoked the handler.
     */
    public static class ForwardingHandler extends ObjectEventHandlerNode {
        public static final AtomicInteger COUNT = new AtomicInteger();
        private MessageSink<String> sink;

        @ServiceRegistered
        public void wire(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s && sink != null) {
                sink.accept("processed:" + s);
                COUNT.incrementAndGet();
            }
            return true;
        }
    }

    @Test
    void file_source_to_handler_to_file_sink_round_trip() throws IOException, InterruptedException {
        Path input = tempDir.resolve("in").resolve("events.jsonl");
        Path output = tempDir.resolve("out").resolve("processed.jsonl");
        Files.createDirectories(input.getParent());

        // Seed input file BEFORE boot — FileEventSource with EARLIEST replays from offset 0.
        Files.writeString(input,
                "alpha\nbeta\ngamma\n",
                StandardCharsets.UTF_8);

        ForwardingHandler.COUNT.set(0);

        FileEventSource source = new FileEventSource();
        source.setFilename(input.toString());
        source.setReadStrategy(ReadStrategy.EARLIEST);

        FileMessageSink sink = new FileMessageSink();
        sink.setFilename(output.toString());

        var feed = EventFeedConfig.builder()
                .instance(source)
                .name("file-feed")
                .broadcast(true)
                .agent("file-source-agent", new SleepingMillisIdleStrategy(1))
                .build();

        var sinkCfg = EventSinkConfig.builder()
                .instance(sink)
                .name("file-sink")
                .build();

        var processor = EventProcessorConfig.builder()
                .customHandler(new ForwardingHandler())
                .name("forwarder")
                .build();

        var app = MongooseServerConfig.builder()
                .addProcessor("processor-agent", processor)
                .addEventFeed(feed)
                .addEventSink(sinkCfg)
                .build();

        MongooseServer server = MongooseServer.bootServer(app);
        try {
            // wait until output file has all 3 lines (3 s timeout)
            long deadline = System.currentTimeMillis() + 3_000;
            List<String> lines = List.of();
            while (System.currentTimeMillis() < deadline) {
                if (Files.exists(output)) {
                    lines = Files.readAllLines(output, StandardCharsets.UTF_8);
                    if (lines.size() >= 3) break;
                }
                Thread.sleep(20);
            }
            assertTrue(Files.exists(output), "output file should exist after round-trip");
            assertEquals(List.of("processed:alpha", "processed:beta", "processed:gamma"), lines,
                    "output file should contain the processed events in source order");
            assertEquals(3, ForwardingHandler.COUNT.get(),
                    "handler should have seen exactly the seeded events");
        } finally {
            server.stop();
        }
    }
}
