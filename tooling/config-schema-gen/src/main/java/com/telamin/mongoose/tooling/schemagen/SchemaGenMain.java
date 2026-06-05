/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.tooling.schemagen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.Schema;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Build/release-time entry point: reads {@code plugin-index.json} +
 * {@code schema-overrides.json} from the classpath, reflects the config surface,
 * and writes {@code schema.json} (latest + version-stamped) to the output dir.
 * <p>
 * Usage: {@code SchemaGenMain <pluginsVersion> <outputDir>}.
 * Run from the module via the exec plugin or {@code mvn -P generate-schema}.
 */
public final class SchemaGenMain {

    static ObjectMapper jsonMapper() {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    }

    static JsonNode loadResource(String name) throws Exception {
        try (InputStream in = SchemaGenMain.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + name);
            }
            return jsonMapper().readTree(in);
        }
    }

    /** Build the schema from the bundled index + overrides. Shared by main + tests. */
    public static Schema buildSchema(String pluginsVersion) throws Exception {
        JsonNode index = loadResource("plugin-index.json");
        JsonNode overrides = loadResource("schema-overrides.json");
        return new SchemaGenerator().generate(index, overrides, pluginsVersion);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: SchemaGenMain <pluginsVersion> <outputDir> [docsDir]");
            System.exit(2);
        }
        String pluginsVersion = args[0];
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);

        Schema schema = buildSchema(pluginsVersion);
        String json = jsonMapper().writeValueAsString(schema) + "\n";

        Files.writeString(outDir.resolve("schema.json"), json, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("schema-" + pluginsVersion + ".json"), json, StandardCharsets.UTF_8);
        System.out.println("[schema-gen] wrote schema.json (" + schema.plugins().size()
                + " plugins) for mongoose-plugins " + pluginsVersion + " -> " + outDir);

        // Optional: publish into the MkDocs docs tree — raw schema.json (latest +
        // version-stamped) for machine consumers, plus a human-readable reference page.
        if (args.length >= 3) {
            Path docs = Path.of(args[2]);
            Path schemaDir = docs.resolve("schema");
            Path refDir = docs.resolve("reference");
            Files.createDirectories(schemaDir);
            Files.createDirectories(refDir);
            Files.writeString(schemaDir.resolve("schema.json"), json, StandardCharsets.UTF_8);
            Files.writeString(schemaDir.resolve("schema-" + pluginsVersion + ".json"), json, StandardCharsets.UTF_8);
            String md = new DocsRenderer().renderMarkdown(schema);
            Files.writeString(refDir.resolve("config-reference.md"), md, StandardCharsets.UTF_8);
            System.out.println("[schema-gen] published docs -> " + docs
                    + " (schema/schema.json, reference/config-reference.md)");
        }
    }
}
