/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.file;

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

    @Test
    void size_based_rotation_creates_backup() throws IOException {
        Path outputFile = tempDir.resolve("out.log");
        TestableFileMessageSink sink = new TestableFileMessageSink();
        sink.setFilename(outputFile.toString());
        sink.setRotateOnSizeBytes(20); // 20 bytes — small enough to trip on first write

        sink.init();
        sink.start();
        sink.write("this-is-a-long-line-that-exceeds-twenty-bytes");
        sink.write("after-rotate-line");
        sink.stop();

        List<String> backups = sink.listRotatedBackups();
        Assertions.assertEquals(1, backups.size(), "expected one rotated backup: " + backups);
        Assertions.assertTrue(Files.exists(outputFile), "active file should still exist");
        Assertions.assertEquals(List.of("after-rotate-line"),
                Files.readAllLines(outputFile, StandardCharsets.UTF_8));
    }

    @Test
    void max_backup_files_prunes_oldest() throws IOException, InterruptedException {
        Path outputFile = tempDir.resolve("out.log");
        TestableFileMessageSink sink = new TestableFileMessageSink();
        sink.setFilename(outputFile.toString());
        sink.setRotateOnSizeBytes(1); // rotate on every write
        sink.setMaxBackupFiles(2);

        sink.init();
        sink.start();
        for (int i = 0; i < 5; i++) {
            sink.write("line-" + i);
            // ensure unique rotation timestamps
            Thread.sleep(1100);
        }
        sink.stop();

        List<String> backups = sink.listRotatedBackups();
        Assertions.assertEquals(2, backups.size(),
                "should retain at most maxBackupFiles=2 backups, got: " + backups);
    }

    @Test
    void stop_is_idempotent() {
        TestableFileMessageSink sink = new TestableFileMessageSink();
        sink.setFilename(tempDir.resolve("idem.log").toString());
        sink.init();
        sink.start();
        sink.stop();
        Assertions.assertDoesNotThrow(sink::stop);
    }

    @Test
    void start_with_negative_rotate_settings_throws() {
        TestableFileMessageSink sink = new TestableFileMessageSink();
        sink.setFilename(tempDir.resolve("x.log").toString());
        sink.init();
        sink.setRotateOnSizeBytes(-1);
        Assertions.assertThrows(IllegalStateException.class, sink::start);
        sink.setRotateOnSizeBytes(0);
        sink.setRotateOnIntervalMillis(-1);
        Assertions.assertThrows(IllegalStateException.class, sink::start);
        sink.setRotateOnIntervalMillis(0);
        sink.setMaxBackupFiles(-1);
        Assertions.assertThrows(IllegalStateException.class, sink::start);
    }
}
