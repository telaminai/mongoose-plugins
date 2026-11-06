/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.file;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LineMapperTest {

    public record Item(String id, int qty, LocalDateTime at) {
    }

    public record Pickup(String type, String id, LocalDateTime at) {
    }

    public record Quote(String sym, double px) {
    }

    static class TextMapper extends LineMapper {
        TextMapper() {
            record("ITEM", 4, f -> new Item(f[1], i(f[2]), at(f[3])));
            record("NULL", 1, f -> null);
            json("PICKUP", Pickup.class);
        }
    }

    private final TextMapper mapper = new TextMapper();

    @Test
    void text_lines_map_by_record_type() {
        assertEquals(new Item("a1", 3, LocalDateTime.parse("2026-01-02T10:00")),
                mapper.apply("ITEM a1 3 2026-01-02T10:00"));
    }

    @Test
    void bad_text_lines_are_rejected() {
        for (String bad : List.of("UNKNOWN x", "ITEM a1 3", "ITEM a1  2026-01-02T10:00", "ITEM a1 x 2026-01-02T10:00",
                "NULL", "")) {
            assertEquals(new Rejected(bad), mapper.apply(bad), bad);
        }
    }

    @Test
    void json_lines_map_by_type_field() {
        assertEquals(new Pickup("PICKUP", "p1", LocalDateTime.parse("2026-01-02T10:00")),
                mapper.apply("{\"type\":\"PICKUP\",\"id\":\"p1\",\"at\":\"2026-01-02T10:00\",\"extra\":1}"));
        assertEquals(new Rejected("{\"type\":\"OTHER\"}"), mapper.apply("{\"type\":\"OTHER\"}"));
        assertEquals(new Rejected("{not json"), mapper.apply("{not json"));
    }

    @Test
    void json_lines_without_a_type_field() {
        LineMapper quotes = new LineMapper() {
            {
                json(Quote.class);
            }
        };
        assertEquals(new Quote("ABC", 1.5), quotes.apply("{\"sym\":\"ABC\",\"px\":1.5}"));
    }

    @Test
    void non_text_input_passes_through() {
        Object event = new Object();
        assertSame(event, mapper.apply(event));
    }
}
