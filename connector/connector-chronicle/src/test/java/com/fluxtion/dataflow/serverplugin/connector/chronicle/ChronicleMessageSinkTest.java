/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.connector.chronicle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class ChronicleMessageSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void init_creates_queue_dir_if_missing() {
        Path queueDir = tempDir.resolve("missing").resolve("nested");
        Assertions.assertFalse(Files.exists(queueDir), "precondition");

        ChronicleMessageSink sink = new ChronicleMessageSink();
        sink.setChroniclePath(queueDir.toString());

        Assertions.assertDoesNotThrow(sink::init);
        Assertions.assertTrue(Files.exists(queueDir.resolve("chronicle-queue")),
                "chronicle-queue directory should be auto-created");

        sink.tearDown();
    }

    @Test
    void init_with_empty_chroniclePath_throws() {
        ChronicleMessageSink sink = new ChronicleMessageSink();
        sink.setChroniclePath("");
        Assertions.assertThrows(IllegalStateException.class, sink::init);
    }

    @Test
    void init_with_null_chroniclePath_throws() {
        ChronicleMessageSink sink = new ChronicleMessageSink();
        sink.setChroniclePath(null);
        Assertions.assertThrows(IllegalStateException.class, sink::init);
    }
}
