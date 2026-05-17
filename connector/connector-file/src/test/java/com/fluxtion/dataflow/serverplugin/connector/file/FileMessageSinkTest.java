/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.connector.file;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class FileMessageSinkTest {

    @TempDir
    Path tempDir;

    /**
     * Expose the protected sendToSink so tests can write.
     */
    static class TestableFileMessageSink extends FileMessageSink {
        public void write(Object value) {
            super.sendToSink(value);
        }
    }

    @Test
    void writesLinesToFile() throws IOException {
        Path outputFile = tempDir.resolve("sink").resolve("out.log");
        TestableFileMessageSink sink = new TestableFileMessageSink();
        sink.setFilename(outputFile.toString());

        sink.init();
        sink.start();
        sink.write("hello");
        sink.write("world");
        sink.stop();

        Assertions.assertEquals(List.of("hello", "world"),
                Files.readAllLines(outputFile, StandardCharsets.UTF_8));
    }

    @Test
    void start_with_bare_basename_does_not_throw() throws IOException {
        // filename has no parent path — used to NPE on getParentFile().mkdirs()
        Path bareFile = Path.of("file-message-sink-bare-basename.tmp");
        Files.deleteIfExists(bareFile);
        try {
            TestableFileMessageSink sink = new TestableFileMessageSink();
            sink.setFilename(bareFile.toString());

            sink.init();
            Assertions.assertDoesNotThrow(sink::start);
            sink.write("hello");
            sink.stop();

            Assertions.assertEquals(List.of("hello"),
                    Files.readAllLines(bareFile, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(bareFile);
        }
    }

    @Test
    void start_with_empty_filename_throws() {
        TestableFileMessageSink sink = new TestableFileMessageSink();
        sink.setFilename("");
        sink.init();
        Assertions.assertThrows(IllegalStateException.class, sink::start);
    }

    @Test
    void start_with_null_filename_throws() {
        TestableFileMessageSink sink = new TestableFileMessageSink();
        sink.setFilename(null);
        sink.init();
        Assertions.assertThrows(IllegalStateException.class, sink::start);
    }
}
