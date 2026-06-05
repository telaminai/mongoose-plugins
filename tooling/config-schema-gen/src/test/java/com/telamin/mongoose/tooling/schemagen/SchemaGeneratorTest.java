/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.tooling.schemagen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.FieldSchema;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.PluginSchema;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.Schema;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 proof + regression lock for the config-schema generator.
 * <p>
 * Two layers: targeted assertions on the reflected surface (readable, explains
 * intent) plus a golden-file comparison (locks the exact emitted JSON so any
 * drift — new field, changed default, filter regression — fails the build).
 */
class SchemaGeneratorTest {

    private static final String VERSION = "test-1.0.0";
    private final ObjectMapper mapper = SchemaGenMain.jsonMapper();

    private Schema build() throws Exception {
        return SchemaGenMain.buildSchema(VERSION);
    }

    private PluginSchema plugin(Schema s, String artifactId, String kind) {
        return s.plugins().stream()
                .filter(p -> p.artifactId().equals(artifactId) && p.kind().equals(kind))
                .findFirst().orElseThrow(() -> new AssertionError("no plugin " + artifactId + ":" + kind));
    }

    private FieldSchema field(PluginSchema p, String name) {
        Optional<FieldSchema> f = p.fields().stream().filter(x -> x.name().equals(name)).findFirst();
        assertTrue(f.isPresent(), () -> "field '" + name + "' missing from " + p.artifactId() + ":" + p.kind()
                + " — present: " + p.fields().stream().map(FieldSchema::name).toList());
        return f.get();
    }

    @Test
    void emitsAllIndexedPlugins() throws Exception {
        Schema s = build();
        assertEquals(SchemaGenerator.SCHEMA_VERSION, s.schemaVersion());
        assertEquals(VERSION, s.pluginsVersion());
        // P2 index: 5 connectors × (source+sink) + svc-cache = 11.
        assertEquals(11, s.plugins().size());
    }

    @Test
    void kafkaCollectionsCarryElementTypes() throws Exception {
        PluginSchema p = plugin(build(), "connector-kafka", "source");
        FieldSchema topics = field(p, "topics");
        assertEquals("list", topics.type(), "String[] topics → list");
        assertEquals("string", topics.elementType());

        FieldSchema props = field(p, "properties");
        assertEquals("map", props.type(), "Properties → map");
        assertEquals("string", props.keyType());
        assertEquals("string", props.valueType());
    }

    @Test
    void fileSourceConfigSurface() throws Exception {
        PluginSchema p = plugin(build(), "connector-file", "source");
        assertEquals("eventFeeds", p.yamlKey());
        assertEquals("instance", p.yamlBindKey());

        // The real config surface: @Setter-annotated fields only.
        FieldSchema filename = field(p, "filename");
        assertEquals("string", filename.type());
        assertTrue(filename.required(), "filename forced required via override");
        assertEquals("path", filename.format());

        FieldSchema readStrategy = field(p, "readStrategy");
        assertEquals("enum", readStrategy.type());
        assertEquals("COMMITED", readStrategy.defaultValue(), "default read off a fresh instance");
        assertTrue(readStrategy.enumValues().contains("EARLIEST"), "enum constants captured");

        assertEquals("boolean", field(p, "cacheEventLog").type());
    }

    @Test
    void serviceUsesServiceBindKey() throws Exception {
        PluginSchema p = plugin(build(), "svc-cache", "service");
        assertEquals("services", p.yamlKey());
        assertEquals("service", p.yamlBindKey(), "services bind under service:, not instance:");
        assertTrue(field(p, "fileName").required());
    }

    @Test
    void frameworkSettersAreFiltered() throws Exception {
        Schema s = build();
        for (PluginSchema p : s.plugins()) {
            for (FieldSchema f : p.fields()) {
                assertFalse(f.name().equals("eventToQueuePublisher")
                                || f.name().equals("eventFlowManager")
                                || f.name().equals("eventToInvokeStrategy"),
                        () -> "framework setter leaked into schema: " + p.artifactId() + "." + f.name());
            }
        }
    }

    @Test
    void matchesGoldenFile() throws Exception {
        // Compare normalised serialised strings (both produced by the same writer)
        // rather than JsonNode trees — tree equality is brittle across int/long
        // numeric node types (IntNode(0) != LongNode(0)). The golden is exactly
        // what SchemaGenMain writes, so re-serialising the generated schema with the
        // same mapper must reproduce it byte-for-byte.
        String actual = mapper.writeValueAsString(build()).strip();

        String expected;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("expected-schema.json")) {
            assertNotNull(in, "golden expected-schema.json missing from test resources");
            expected = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        }
        assertEquals(expected, actual,
                "emitted schema drifted from golden expected-schema.json. If intended, "
                        + "regenerate it (SchemaGenMain) and review the diff before committing.");
    }
}
