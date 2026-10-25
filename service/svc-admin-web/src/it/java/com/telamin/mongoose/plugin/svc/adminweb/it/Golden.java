/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A golden snapshot of the REST contract for one scenario: each labelled exchange's status and body, keys
 * sorted, volatile values (capture ids, temp paths, object hashes, timestamps, live thread states, sizes)
 * replaced by placeholders. {@code -Dgolden.update=true} rewrites the file; review the diff before committing.
 */
final class Golden {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final String scenario;
    private final ScenarioServer server;
    private final Map<String, Object> exchanges = new LinkedHashMap<>();

    Golden(String scenario, ScenarioServer server) {
        this.scenario = scenario;
        this.server = server;
    }

    /** Record an exchange; returns the response for further fact assertions. */
    HttpResponse<String> record(String label, HttpResponse<String> r) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("status", r.statusCode());
        String body = r.body();
        Object parsed;
        try {
            parsed = body == null || body.isBlank() ? "" : JSON.readValue(body, Object.class);
        } catch (Exception notJson) {
            parsed = body;
        }
        e.put("body", sortCaptureHandles(parsed));
        exchanges.put(label, e);
        return r;
    }

    /**
     * {@code GET /api/audit/files} lists capture handles in directory order, which follows the random capture ids;
     * order them by selector so the golden pins content, not that accident.
     */
    @SuppressWarnings("unchecked")
    private static Object sortCaptureHandles(Object parsed) {
        if (parsed instanceof java.util.List<?> list && !list.isEmpty()
                && list.stream().allMatch(o -> o instanceof Map<?, ?> m && m.containsKey("processorName"))) {
            java.util.List<Map<String, Object>> copy = new java.util.ArrayList<>((java.util.List<Map<String, Object>>) list);
            copy.sort(java.util.Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("processorName")))
                    .thenComparing(m -> String.valueOf(m.get("isLive"))));
            return copy;
        }
        return parsed;
    }

    String normalised() throws Exception {
        String text = JSON.writeValueAsString(exchanges);
        for (Path p : new Path[]{server.workDir, server.workDir.toRealPath()}) {
            text = text.replace(p.toString(), "<WORK>");
        }
        text = replace(text, "capture-[0-9]+", "capture-<ID>");
        text = replace(text, "@[0-9a-f]{4,}", "@<HASH>");
        text = replace(text, "#[0-9a-f]{6,}", "#<HASH>");
        text = replace(text, "Thread-[0-9]+", "Thread-<N>");
        text = replace(text, "\"(startedAt|lastWriteAt)\" : \"[^\"]*\"", "\"$1\" : \"<TS>\"");
        text = replace(text, "\"(cycle|sizeBytes|recordCount)\" : [0-9]+", "\"$1\" : \"<N>\"");
        text = replace(text, "\"threadState\" : \"(RUNNABLE|TIMED_WAITING|WAITING|BLOCKED)\"", "\"threadState\" : \"<LIVE>\"");
        text = replace(text, "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<UUID>");
        text = replace(text, "  [0-9]+ bytes", "  <N> bytes");
        text = replace(text, "\"(pid|runtime)\" : \"[^\"]*\"", "\"$1\" : \"<$1>\"");
        text = replace(text, "\"(startTime|uptimeMs)\" : [0-9]+", "\"$1\" : \"<N>\"");
        return text + "\n";
    }

    private static String replace(String text, String regex, String with) {
        return Pattern.compile(regex).matcher(text).replaceAll(with);
    }

    /** Compare against {@code it/golden/<scenario>.json}, or write it with {@code -Dgolden.update=true}. */
    void verify() throws Exception {
        String actual = normalised();
        Path dir = Path.of(System.getProperty("it.golden.dir", "src/it/resources/it/golden"));
        Path file = dir.resolve(scenario + ".json");
        Path artifacts = Files.createDirectories(Path.of(System.getProperty("it.artifacts.dir", "target/admin-web-it"), "rest"));
        Files.writeString(artifacts.resolve(scenario + ".actual.json"), actual);
        if (Boolean.getBoolean("golden.update")) {
            Files.createDirectories(dir);
            Files.writeString(file, actual);
            return;
        }
        assertTrue(Files.exists(file), "no golden file " + file + "; run once with -Dgolden.update=true and review it");
        assertEquals(Files.readString(file), actual, "REST contract for scenario " + scenario
                + " changed; actual written to " + artifacts.resolve(scenario + ".actual.json"));
    }
}
