/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.tooling.schemagen;

import com.telamin.mongoose.tooling.schemagen.SchemaModel.FieldSchema;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.PluginSchema;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.Schema;

import java.util.List;

/**
 * Renders a {@link Schema} to a human-readable MkDocs "Config reference" page —
 * the inspectable companion to the machine-readable {@code schema.json}. Grouped by
 * connectors / services, a table per plugin, nested objects expanded inline.
 * Deterministic (driven only by the schema), so it can't drift from the code.
 * See design/connector-config-schema.md §6.
 */
public final class DocsRenderer {

    public String renderMarkdown(Schema schema) {
        StringBuilder b = new StringBuilder();
        b.append("# Connector & service config reference\n\n");
        b.append("!!! note \"Generated\"\n");
        b.append("    This page is generated from the plugin config classes by\n");
        b.append("    `tooling/config-schema-gen` — do not edit by hand. The same data,\n");
        b.append("    machine-readable, is at [`schema.json`](../schema/schema.json)\n");
        b.append("    (consumed by the Fluxtion project starter).\n\n");
        b.append("Plugins release **`").append(schema.pluginsVersion()).append("`**. ");
        b.append("Feeds bind under `eventFeeds:` / sinks under `eventSinks:` (key `instance:`); ");
        b.append("services under `services:` (key `service:`).\n\n");

        b.append("## Connectors\n\n");
        for (PluginSchema p : schema.plugins()) {
            if ("source".equals(p.kind()) || "sink".equals(p.kind())) {
                renderPlugin(b, p);
            }
        }
        b.append("## Services\n\n");
        for (PluginSchema p : schema.plugins()) {
            if ("service".equals(p.kind())) {
                renderPlugin(b, p);
            }
        }
        return b.toString();
    }

    private void renderPlugin(StringBuilder b, PluginSchema p) {
        String title = p.instanceFqn().substring(p.instanceFqn().lastIndexOf('.') + 1);
        b.append("### ").append(title).append("  <small>`").append(p.artifactId())
                .append("` · ").append(p.kind()).append("</small>\n\n");
        if (p.doc() != null) {
            b.append(p.doc()).append("\n\n");
        }
        b.append("- **Class**: `").append(p.instanceFqn()).append("`\n");
        b.append("- **YAML**: `").append(p.yamlKey()).append(":` → `").append(p.yamlBindKey()).append(":`\n");
        if (p.sourceVersion() != null) {
            b.append("- **From**: mongoose-core `").append(p.sourceVersion()).append("`\n");
        }
        b.append('\n');
        if (p.fields().isEmpty()) {
            b.append("_No configurable fields._\n\n");
            return;
        }
        renderFieldTable(b, p.fields());
        // Expand nested objects below the table.
        for (FieldSchema f : p.fields()) {
            if (f.fields() != null && !f.fields().isEmpty()) {
                b.append("\n*`").append(f.name()).append("` ")
                        .append("map".equals(f.type()) ? "value" : "element")
                        .append(" object:*\n\n");
                renderFieldTable(b, f.fields());
            }
        }
        b.append('\n');
    }

    private void renderFieldTable(StringBuilder b, List<FieldSchema> fields) {
        b.append("| Field | Type | Required | Default | Allowed / notes |\n");
        b.append("|---|---|:--:|---|---|\n");
        for (FieldSchema f : fields) {
            b.append("| `").append(f.name()).append("` | ").append(typeLabel(f)).append(" | ")
                    .append(f.required() ? "✔" : "").append(" | ")
                    .append(f.defaultValue() == null ? "" : "`" + f.defaultValue() + "`").append(" | ")
                    .append(notes(f)).append(" |\n");
        }
    }

    private String typeLabel(FieldSchema f) {
        return switch (f.type()) {
            case "list" -> "list&lt;" + n(f.elementType()) + "&gt;";
            case "map" -> "map&lt;" + n(f.keyType()) + "," + n(f.valueType()) + "&gt;";
            default -> f.type();
        };
    }

    private String notes(FieldSchema f) {
        StringBuilder n = new StringBuilder();
        if (f.enumValues() != null && !f.enumValues().isEmpty()) {
            n.append(String.join(" \\| ", f.enumValues().stream().map(v -> "`" + v + "`").toList()));
        }
        if (f.format() != null) {
            if (n.length() > 0) n.append("<br>");
            n.append("_format: ").append(f.format()).append("_");
        }
        if (f.doc() != null) {
            if (n.length() > 0) n.append("<br>");
            n.append(escape(f.doc()));
        }
        if (f.fields() != null && !f.fields().isEmpty()) {
            if (n.length() > 0) n.append("<br>");
            n.append("_object — see below_");
        }
        return n.toString();
    }

    private static String n(String s) {
        return s == null ? "ref" : s;
    }

    private static String escape(String s) {
        return s.replace("|", "\\|");
    }
}
