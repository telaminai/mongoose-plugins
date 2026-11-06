/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.lib.json;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateJsonTest {

    public enum Side { BUY, SELL }

    public record Times(LocalDateTime at, LocalDate day, LocalTime time, Instant instant, Duration took, Side side,
                        List<String> tags) {
    }

    public record Small(String name) {
    }

    @Test
    void java_time_values_round_trip_as_iso_strings() throws Exception {
        Times t = new Times(LocalDateTime.parse("2026-01-02T03:04:05"), LocalDate.parse("2026-01-02"),
                LocalTime.parse("10:15"), Instant.parse("2026-01-02T03:04:05Z"), Duration.ofSeconds(90), Side.SELL,
                List.of("a", "b"));
        String json = StateJson.mapper().writeValueAsString(t);
        assertEquals("{\"at\":\"2026-01-02T03:04:05\",\"day\":\"2026-01-02\",\"time\":\"10:15\","
                + "\"instant\":\"2026-01-02T03:04:05Z\",\"took\":\"PT1M30S\",\"side\":\"SELL\",\"tags\":[\"a\",\"b\"]}", json);
        assertEquals(t, StateJson.mapper().readValue(json, Times.class));
    }

    @Test
    void unknown_properties_are_ignored() throws Exception {
        assertEquals(new Small("x"), StateJson.mapper().readValue("{\"name\":\"x\",\"extra\":1}", Small.class));
    }

    @Test
    void line_writes_text_as_is_and_objects_as_one_json_line() {
        assertEquals("plain text", StateJson.line("plain text"));
        assertEquals("42", StateJson.line(42));
        assertEquals("true", StateJson.line(true));
        assertEquals("null", StateJson.line(null));
        assertEquals("{\"k\":\"2026-01-02\"}", StateJson.line(Map.of("k", LocalDate.parse("2026-01-02"))));
    }
}
