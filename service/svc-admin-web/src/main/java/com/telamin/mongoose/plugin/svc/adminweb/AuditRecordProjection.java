/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML → JSON projection for audit records read from Chronicle.
 *
 * <p>The on-disk format (see ChronicleAuditCaptureService) is a
 * sequence of Chronicle excerpts whose text payload is the YAML
 * representation of one Fluxtion {@code LogRecord} (the runtime
 * pre-assembles it). For the paged-read + live-tail endpoints we
 * project each YAML record to a JSON object — the front-end
 * eventlog-parser.js consumes NDJSON, not YAML, so the projection
 * runs on the server side (off the processor's agent thread) where
 * SnakeYAML parsing is acceptable cost.
 *
 * <p>Static state: one {@link Yaml} loader + one {@link ObjectMapper}.
 * Both are documented as thread-safe for the read/write methods we
 * use. Saves a hot-path allocation per request.
 */
final class AuditRecordProjection {

    private static final Yaml YAML = new Yaml();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Fields that may carry free-form scalar text (toString output,
     *  method signatures with annotations, etc.) and trip SnakeYAML's
     *  plain-scalar reserved-char rules — must be rewritten as block
     *  literals before parsing. */
    private static final String[] FREEFORM_STRING_FIELDS = {
            "eventToString",
            "event",
            "thread",
            "groupingId"
    };

    private AuditRecordProjection() {
    }

    /**
     * Parse one YAML record (as written by ChronicleAuditCaptureService)
     * and serialise it as JSON. Returns the JSON text without a trailing
     * newline — the caller wraps the newline if it's emitting NDJSON.
     *
     * <p>Fluxtion's {@code LogRecord} renders some field values
     * un-quoted (the toString output is dropped in verbatim), so a
     * value that starts with {@code @} (e.g. a method signature like
     * {@code @Override public void ...}) trips SnakeYAML's scanner.
     * We {@link #fixupFreeformScalars pre-process} the YAML to rewrite
     * known free-form scalar fields as block-literal style — that
     * tolerates any character (reserved or not) and any line count
     * without escaping.
     *
     * <p>If pre-processing still leaves the record unparseable we fall
     * back to a {@code {"raw": ..., "_parseError": ...}} wrapper so the
     * UI still surfaces the record and the rest of the file keeps
     * streaming.
     */
    static String yamlToJson(String yaml) throws java.io.IOException {
        String preprocessed = fixupFreeformScalars(yaml);
        try {
            Object tree = YAML.load(preprocessed);
            return JSON.writeValueAsString(tree);
        } catch (RuntimeException scannerOrParserError) {
            return JSON.writeValueAsString(java.util.Map.of(
                    "raw", yaml,
                    "_parseError", scannerOrParserError.getClass().getSimpleName()
                            + ": " + scannerOrParserError.getMessage()));
        }
    }

    /**
     * Rewrite known free-form string fields in the YAML to block-literal
     * (<code>|</code>) style. Block literals carry their content verbatim
     * — no plain-scalar restrictions, any character is allowed, any
     * number of continuation lines is folded into the value.
     *
     * <p>Detection is indent-aware: a field is identified when its line
     * starts with the field's indent prefix plus its name and a colon.
     * Continuation lines are anything at the same or deeper indent until
     * the next sibling field key is seen (matching the surrounding
     * {@code <indent><word>:} pattern).
     */
    static String fixupFreeformScalars(String yaml) {
        if (yaml == null || yaml.isEmpty()) return yaml;
        String[] lines = yaml.split("\n", -1);
        StringBuilder out = new StringBuilder(yaml.length() + 64);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            FieldMatch m = matchFreeformField(line);
            if (m == null) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                i++;
                continue;
            }

            // Collect continuation lines until the next sibling field
            // (line whose indent equals the matched field's indent AND
            // matches a `<word>:` pattern). Everything else at any
            // indent is part of the current value.
            List<String> valueLines = new ArrayList<>();
            valueLines.add(m.value);

            int j = i + 1;
            while (j < lines.length) {
                String nxt = lines[j];
                if (isSiblingFieldKey(nxt, m.indent)) break;
                valueLines.add(nxt);
                j++;
            }

            // Emit as block-literal with strip chomping (|-). The block-
            // content indent must be greater than the field's indent —
            // pick fieldIndent + 4 so it dominates whatever indent the
            // captured value lines had. `|-` (vs `|`) strips the trailing
            // newline so single-word values like `event: Trade` don't
            // come out as `"Trade\n"` in the parsed JSON.
            String contentIndent = m.indent + "    ";
            out.append(m.indent).append(m.name).append(": |-\n");
            for (String v : valueLines) {
                // Trim trailing CR (Windows line endings); leave content
                // otherwise verbatim — block-literal preserves it.
                String content = v.endsWith("\r") ? v.substring(0, v.length() - 1) : v;
                out.append(contentIndent).append(content).append('\n');
            }
            i = j;
        }
        return out.toString();
    }

    /** Look for `<indent><freeformField>: <value>` at the start of `line`.
     *  Returns the match details or {@code null} when no free-form field
     *  is at the start of the line. */
    private static FieldMatch matchFreeformField(String line) {
        int indentEnd = 0;
        while (indentEnd < line.length() && line.charAt(indentEnd) == ' ') indentEnd++;
        if (indentEnd == 0 || indentEnd == line.length()) return null;
        String indent = line.substring(0, indentEnd);
        String rest = line.substring(indentEnd);
        for (String field : FREEFORM_STRING_FIELDS) {
            String prefix = field + ":";
            if (!rest.startsWith(prefix)) continue;
            // Field key matched. The value starts after `: ` (may be empty).
            int valStart = field.length() + 1;
            if (valStart < rest.length() && rest.charAt(valStart) == ' ') valStart++;
            String value = valStart < rest.length() ? rest.substring(valStart) : "";
            return new FieldMatch(indent, field, value);
        }
        return null;
    }

    /** Is {@code line} a YAML key at the same indent as our field?
     *  i.e. {@code <sameIndent><word>:...}. Used to detect when the
     *  current free-form value ends. */
    private static boolean isSiblingFieldKey(String line, String indent) {
        if (!line.startsWith(indent)) return false;
        int p = indent.length();
        // The character after the indent must not be another space —
        // that would be a deeper nesting, i.e. still part of the value.
        if (p < line.length() && line.charAt(p) == ' ') return false;
        // Look for a key (`<word>:`).
        int colon = line.indexOf(':', p);
        if (colon < 0) return false;
        for (int k = p; k < colon; k++) {
            char c = line.charAt(k);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return false;
        }
        return colon > p;
    }

    private static final class FieldMatch {
        final String indent;
        final String name;
        final String value;
        FieldMatch(String indent, String name, String value) {
            this.indent = indent;
            this.name = name;
            this.value = value;
        }
    }
}
