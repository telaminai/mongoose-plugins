/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telamin.mongoose.plugin.lib.json.StateJson;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A feed {@code valueMapper} driven by a table: register each record type with its field count and a constructor, e.g.
 * {@code record("ITEM", 4, f -> new ItemRegistered(f[1], f[2], at(f[3])));}. Fields are split on single spaces and
 * f[0] is the record type. An unknown type, a wrong field count, an empty field or any exception while mapping
 * gives {@link Rejected}(line), which a node can handle like any event. Non-text input passes through unchanged. A
 * trailing carriage return (a line of a CRLF file) is dropped before mapping.
 *
 * <p>JSON lines: a line starting with <code>{</code> is JSON. Register each class with the value of its
 * {@link #typeField()} ({@code "type"} by default), e.g. {@code json("PICKUP", PickupRequest.class);}, or
 * {@code json(Quote.class)} for a feed with no type field. The JSON fields fill the record's components by name
 * (unknown fields are ignored; java.time values as ISO strings, see {@link StateJson}). Malformed JSON or an
 * unregistered type gives Rejected: a line whose type field names no registered type is rejected even when
 * {@code json(cls)} is registered, which takes only lines with no type field.
 *
 * <pre>
 * public class AppLineMapper extends LineMapper {
 *     public AppLineMapper() {
 *         record("ITEM", 4, f -&gt; new ItemRegistered(f[1], f[2], at(f[3])));
 *         json("PICKUP", PickupRequest.class);
 *     }
 * }
 * </pre>
 * Configured on a feed as {@code valueMapper: !!com.example.AppLineMapper {}}.
 */
public abstract class LineMapper implements Function<Object, Object> {

    private static final ObjectMapper JSON = StateJson.mapper();

    private record Spec(int fields, Function<String[], Object> mapper) {
    }

    private final Map<String, Spec> specs = new HashMap<>();
    private final Map<String, Class<?>> jsonTypes = new HashMap<>();

    /** A text line whose first field is {@code type} and that has {@code fields} fields maps through {@code mapper}. */
    protected void record(String type, int fields, Function<String[], Object> mapper) {
        specs.put(type, new Spec(fields, mapper));
    }

    /** A JSON line whose {@link #typeField()} is {@code type} maps to {@code cls}. */
    protected void json(String type, Class<?> cls) {
        jsonTypes.put(type, cls);
    }

    /** JSON lines with no type field map to {@code cls}. */
    protected void json(Class<?> cls) {
        jsonTypes.put("", cls);
    }

    /** The JSON field naming a line's type. */
    protected String typeField() {
        return "type";
    }

    @Override
    public Object apply(Object input) {
        if (!(input instanceof String text)) {
            return input;
        }
        String line = text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;   // a CRLF file's line
        if (line.startsWith("{")) {
            return mapJson(line);
        }
        String[] f = line.split(" ", -1);
        Spec spec = specs.get(f[0]);
        if (spec == null || f.length != spec.fields() || Arrays.asList(f).contains("")) {
            return new Rejected(line);
        }
        try {
            Object event = spec.mapper().apply(f);
            return event == null ? new Rejected(line) : event;
        } catch (RuntimeException e) {
            return new Rejected(line);
        }
    }

    private Object mapJson(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            JsonNode type = node.get(typeField());
            Class<?> cls = jsonTypes.get(type == null ? "" : type.asText());
            if (cls == null) {
                return new Rejected(line);
            }
            Object event = JSON.treeToValue(node, cls);
            return event == null ? new Rejected(line) : event;
        } catch (Exception e) {
            return new Rejected(line);
        }
    }

    public static LocalDateTime at(String s) {
        return LocalDateTime.parse(s);
    }

    public static LocalDate date(String s) {
        return LocalDate.parse(s);
    }

    public static int i(String s) {
        return Integer.parseInt(s);
    }

    public static long l(String s) {
        return Long.parseLong(s);
    }

    public static double d(String s) {
        return Double.parseDouble(s);
    }
}
