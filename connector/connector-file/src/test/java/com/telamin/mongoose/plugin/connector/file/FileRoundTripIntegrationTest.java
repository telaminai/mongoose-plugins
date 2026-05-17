/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.file;

import com.telamin.mongoose.plugin.testsupport.MongooseTestHarness;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.config.ReadStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end round-trip through a real {@link com.telamin.mongoose.MongooseServer}
 * using the {@link MongooseTestHarness}: input file → {@link FileEventSource} →
 * handler → {@link FileMessageSink} → output file.
 */
class FileRoundTripIntegrationTest {

    @TempDir
    Path tempDir;

    public static class ForwardingHandler extends ObjectEventHandlerNode {
        private MessageSink<String> sink;

        @ServiceRegistered
        public void wire(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s && sink != null) {
                sink.accept("processed:" + s);
            }
            return true;
        }
    }

    @Test
    void file_source_to_handler_to_file_sink_round_trip() throws IOException {
        Path input = tempDir.resolve("in").resolve("events.jsonl");
        Path output = tempDir.resolve("out").resolve("processed.jsonl");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);

        FileEventSource source = new FileEventSource();
        source.setFilename(input.toString());
        source.setReadStrategy(ReadStrategy.EARLIEST);

        FileMessageSink sink = new FileMessageSink();
        sink.setFilename(output.toString());

        try (MongooseTestHarness h = MongooseTestHarness.builder()
                .feed("file-feed", source, "file-source-agent")
                .sink("file-sink", sink)
                .processor("processor-agent", "forwarder", new ForwardingHandler())
                .start()) {

            h.awaitFileLines(output, 3);

            assertEquals(List.of("processed:alpha", "processed:beta", "processed:gamma"),
                    Files.readAllLines(output, StandardCharsets.UTF_8));
        }
    }
}
