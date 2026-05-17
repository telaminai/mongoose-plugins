/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.file;

import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

class FileEventSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void start_creates_readpointer_parent_dir_if_missing() {
        Path missingDir = tempDir.resolve("missing").resolve("nested");
        Path dataFile = missingDir.resolve("events.txt");
        Path readPointer = Paths.get(dataFile + ".readPointer");

        Assertions.assertFalse(Files.exists(missingDir), "precondition: parent dir should not exist");

        FileEventSource source = new FileEventSource();
        source.setFilename(dataFile.toString());
        source.setReadStrategy(ReadStrategy.COMMITED);

        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileEventFeed");
        source.setOutput(publisher);

        Assertions.assertDoesNotThrow(source::start);
        Assertions.assertTrue(Files.exists(readPointer),
                "readPointer should be created in the auto-created parent dir");

        source.stop();
        source.tearDown();
    }

    @Test
    void start_with_empty_filename_throws() {
        FileEventSource source = new FileEventSource();
        source.setFilename("");
        source.setOutput(new EventToQueuePublisher<>("fileEventFeed"));
        Assertions.assertThrows(IllegalStateException.class, source::start);
    }

    @Test
    void start_with_null_filename_throws() {
        FileEventSource source = new FileEventSource();
        source.setFilename(null);
        source.setOutput(new EventToQueuePublisher<>("fileEventFeed"));
        Assertions.assertThrows(IllegalStateException.class, source::start);
    }
}
