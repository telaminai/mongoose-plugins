/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.tooling.schemagen;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The emitted schema.json model. This is the contract consumed by the Fluxtion
 * Project Starter and published to the docs site — see
 * design/connector-config-schema.md §2. Serialised with NON_NULL / NON_EMPTY so
 * absent fields drop out and the output stays minimal + deterministic.
 */
public final class SchemaModel {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"schemaVersion", "pluginsVersion", "plugins"})
    public record Schema(int schemaVersion, String pluginsVersion, List<PluginSchema> plugins) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"artifactId", "kind", "yamlKey", "yamlBindKey", "instanceFqn", "doc", "fields"})
    public record PluginSchema(
            String artifactId,
            String kind,
            String yamlKey,
            String yamlBindKey,
            String instanceFqn,
            String doc,
            List<FieldSchema> fields) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "type", "required", "default", "enumValues", "format", "doc"})
    public record FieldSchema(
            String name,
            String type,
            boolean required,
            @JsonProperty("default") Object defaultValue,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> enumValues,
            String format,
            String doc) {
    }
}
