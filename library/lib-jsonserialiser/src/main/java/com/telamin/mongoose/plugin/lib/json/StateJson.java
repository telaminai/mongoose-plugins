/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.lib.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.function.Function;

/**
 * The JSON used for application state and JSON lines: records, collections and enums, with {@code java.time} values
 * ({@link LocalDateTime}, {@link LocalDate}, {@link LocalTime}, {@link Instant}, {@link Duration}) written as ISO
 * strings and read back from them, and unknown properties ignored on read. One shared mapper, so a node's saved state
 * ({@code PersistentNode} in svc-cache), a feed's JSON lines ({@code LineMapper} in connector-file), a JSON-lines sink
 * ({@code JsonLineSink}) and the in-process fixture runner (mongoose-test-support) all read and write alike.
 * <p>
 * The mapper is shared: use it, do not reconfigure it. Use {@link #newMapper()} for a mapper of your own.
 */
public final class StateJson {

    private static final ObjectMapper MAPPER = newMapper();

    private StateJson() {
    }

    /** The shared mapper. Thread safe; do not reconfigure it. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** A new mapper configured as {@link #mapper()}. */
    public static ObjectMapper newMapper() {
        return new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .registerModule(timeModule());
    }

    /**
     * The line a value is written as by a JSON-lines sink: a String, number, boolean or null as its text, anything else
     * (records, maps, lists) as one line of JSON; a value Jackson cannot write falls back to {@code toString()}.
     */
    public static String line(Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static SimpleModule timeModule() {
        SimpleModule m = new SimpleModule("state-json-time");
        add(m, LocalDateTime.class, LocalDateTime::parse);
        add(m, LocalDate.class, LocalDate::parse);
        add(m, LocalTime.class, LocalTime::parse);
        add(m, Instant.class, Instant::parse);
        add(m, Duration.class, Duration::parse);
        return m;
    }

    private static <T> void add(SimpleModule m, Class<T> type, Function<String, T> parse) {
        m.addSerializer(type, new JsonSerializer<>() {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializerProvider p) throws IOException {
                gen.writeString(value.toString());
            }
        });
        m.addDeserializer(type, new JsonDeserializer<>() {
            @Override
            public T deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
                return parse.apply(parser.getValueAsString());
            }
        });
    }
}
