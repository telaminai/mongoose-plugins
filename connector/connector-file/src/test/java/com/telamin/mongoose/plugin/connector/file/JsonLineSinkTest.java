/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonLineSinkTest {

    @TempDir
    Path tempDir;

    public record Alert(String id, LocalDate day, List<Integer> counts) {
    }

    @Test
    void strings_as_is_objects_as_one_json_line() throws Exception {
        Path out = tempDir.resolve("run/output.txt");
        JsonLineSink sink = new JsonLineSink();
        sink.setFilename(out.toString());
        sink.init();
        sink.start();
        sink.accept("plain line");
        sink.accept(new Alert("a1", LocalDate.parse("2026-01-02"), List.of(1, 2)));
        sink.accept(7);
        sink.stop();

        assertEquals(List.of("plain line", "{\"id\":\"a1\",\"day\":\"2026-01-02\",\"counts\":[1,2]}", "7"),
                Files.readAllLines(out));
        assertEquals("{\"id\":\"a1\",\"day\":\"2026-01-02\",\"counts\":[1,2]}",
                JsonLineSink.format(new Alert("a1", LocalDate.parse("2026-01-02"), List.of(1, 2))));
    }
}
