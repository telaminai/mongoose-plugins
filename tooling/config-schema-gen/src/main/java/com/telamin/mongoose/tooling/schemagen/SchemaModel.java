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
    @JsonPropertyOrder({"artifactId", "kind", "yamlKey", "yamlBindKey", "instanceFqn", "sourceVersion", "doc", "fields"})
    public record PluginSchema(
            String artifactId,
            String kind,
            String yamlKey,
            String yamlBindKey,
            String instanceFqn,
            /** Which release the class came from. null ⇒ the schema's pluginsVersion
             *  (the common case). Set for mongoose-core built-ins, which move on the
             *  independent {@code mongoose} axis — see design §8.5. */
            String sourceVersion,
            String doc,
            List<FieldSchema> fields) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "type", "required", "default", "enumValues", "enumClass",
            "elementType", "keyType", "valueType", "format", "doc", "fields"})
    public record FieldSchema(
            String name,
            String type,
            boolean required,
            @JsonProperty("default") Object defaultValue,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> enumValues,
            /** Fully-qualified enum class — for type=enum (or an enum list element /
             *  map value), so authoring tools can emit typed Enum.CONSTANT references. */
            @JsonInclude(JsonInclude.Include.NON_NULL) String enumClass,
            /** For type=list: the element type (string/int/…/ref/nested). */
            String elementType,
            /** For type=map: key + value types (value may be nested). */
            String keyType,
            String valueType,
            String format,
            String doc,
            /** The nested object's fields, when this field (type=nested) — or its
             *  list element / map value (elementType/valueType=nested) — is a
             *  mongoose-owned config POJO. See design §3 / §8. */
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldSchema> fields) {
    }
}
