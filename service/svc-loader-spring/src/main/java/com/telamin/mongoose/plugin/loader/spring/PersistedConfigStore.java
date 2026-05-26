/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.loader.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Disk-backed registry of configs the operator has marked persistent.
 *  Layout: {@code <base>/<group>/<file>} plus a sibling
 *  {@code <file>.meta.json} carrying enabled / compile / createdAt /
 *  lastError. Duplicated verbatim in the spring loader — kept here
 *  inline to avoid adding a new shared module. */
final class PersistedConfigStore {

    /** Group + file segments are filename-only — anything that could
     *  escape the base dir or smuggle a path separator is rejected
     *  upstream of any filesystem call. */
    private static final Pattern GROUP = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern FILE  = Pattern.compile("[A-Za-z0-9_.-]{1,256}");

    private PersistedConfigStore() {}

    /** Loaded entry. {@code lastError} is non-null iff the last boot
     *  replay (or persist attempt) raised — surfaced in the admin
     *  UI so an operator can recover without SSH'ing in. */
    static final class Entry {
        final String group;
        final String file;
        final boolean enabled;
        final boolean compile;
        final String createdAt;
        final String lastError;

        Entry(String group, String file, boolean enabled, boolean compile,
              String createdAt, String lastError) {
            this.group = group;
            this.file = file;
            this.enabled = enabled;
            this.compile = compile;
            this.createdAt = createdAt;
            this.lastError = lastError;
        }
    }

    /** Resolves the operator-configured dir to an absolute path,
     *  creating it if missing. Returns null when {@code configured}
     *  is blank — caller treats that as "persistence disabled". */
    static Path resolveBaseDir(String configured) throws IOException {
        if (configured == null || configured.isBlank()) return null;
        Path p = Paths.get(configured).toAbsolutePath();
        Files.createDirectories(p);
        return p;
    }

    static void requireValidGroup(String group) {
        if (group == null || !GROUP.matcher(group).matches()) {
            throw new IllegalArgumentException("invalid group: " + group);
        }
    }

    static void requireValidFile(String file) {
        if (file == null || !FILE.matcher(file).matches()) {
            throw new IllegalArgumentException("invalid file: " + file);
        }
    }

    /** Copies {@code source} into {@code <base>/<group>/<basename>}
     *  and writes a fresh meta.json. Overwrites any previous copy
     *  (re-persisting the same group/file is an update, not an
     *  error). */
    static Entry persist(Path base, String group, Path source, boolean compile) throws IOException {
        requireValidGroup(group);
        String file = source.getFileName().toString();
        requireValidFile(file);

        Path groupDir = base.resolve(group);
        Files.createDirectories(groupDir);

        Path dest = groupDir.resolve(file);
        if (!dest.normalize().startsWith(base)) {
            throw new IllegalArgumentException("destination escapes base dir");
        }
        Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        String createdAt = Instant.now().toString();
        Entry meta = new Entry(group, file, true, compile, createdAt, null);
        writeMeta(base, meta);
        return meta;
    }

    static List<Entry> list(Path base) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (base == null || !Files.isDirectory(base)) return out;
        try (var groups = Files.list(base)) {
            List<Path> groupDirs = groups
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
            for (Path groupDir : groupDirs) {
                String group = groupDir.getFileName().toString();
                if (!GROUP.matcher(group).matches()) continue;
                try (var files = Files.list(groupDir)) {
                    List<Path> sourceFiles = files
                            .filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().endsWith(".meta.json"))
                            .sorted(Comparator.comparing(Path::getFileName))
                            .toList();
                    for (Path src : sourceFiles) {
                        String file = src.getFileName().toString();
                        if (!FILE.matcher(file).matches()) continue;
                        out.add(readMeta(base, group, file));
                    }
                }
            }
        }
        return out;
    }

    static Entry setEnabled(Path base, String group, String file, boolean enabled) throws IOException {
        requireValidGroup(group); requireValidFile(file);
        Entry current = readMeta(base, group, file);
        Entry next = new Entry(group, file, enabled, current.compile, current.createdAt, current.lastError);
        writeMeta(base, next);
        return next;
    }

    static void remove(Path base, String group, String file) throws IOException {
        requireValidGroup(group); requireValidFile(file);
        Path groupDir = base.resolve(group);
        Path src = groupDir.resolve(file);
        if (!src.normalize().startsWith(base)) {
            throw new IllegalArgumentException("path escapes base dir");
        }
        Files.deleteIfExists(src);
        Files.deleteIfExists(groupDir.resolve(file + ".meta.json"));
        // Remove the group dir if empty so the list view stays tidy
        try (var s = Files.list(groupDir)) {
            if (s.findAny().isEmpty()) Files.deleteIfExists(groupDir);
        } catch (NoSuchFileException ignored) {}
    }

    static String readSource(Path base, String group, String file) throws IOException {
        requireValidGroup(group); requireValidFile(file);
        Path p = base.resolve(group).resolve(file);
        if (!p.normalize().startsWith(base)) {
            throw new IllegalArgumentException("path escapes base dir");
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    static Path resolveAbsoluteSourcePath(Path base, String group, String file) {
        requireValidGroup(group); requireValidFile(file);
        Path p = base.resolve(group).resolve(file);
        if (!p.normalize().startsWith(base)) {
            throw new IllegalArgumentException("path escapes base dir");
        }
        return p;
    }

    static void updateLastError(Path base, String group, String file, String error) throws IOException {
        Entry current = readMeta(base, group, file);
        Entry next = new Entry(group, file, current.enabled, current.compile, current.createdAt, error);
        writeMeta(base, next);
    }

    // ---- metadata read/write — hand-rolled JSON to avoid pulling
    //      jackson into the loaders. The schema is fixed; a real JSON
    //      lib would be overkill. ----

    private static Entry readMeta(Path base, String group, String file) throws IOException {
        Path metaPath = base.resolve(group).resolve(file + ".meta.json");
        if (!Files.exists(metaPath)) {
            // Missing meta = legacy / hand-placed file → assume enabled
            // and compile-true, no createdAt. Surface the gap as a
            // non-fatal lastError so the operator can re-persist
            // through the UI if they want a clean record.
            return new Entry(group, file, true, true, null, "meta.json missing");
        }
        String json = Files.readString(metaPath, StandardCharsets.UTF_8);
        boolean enabled = readBool(json, "enabled", true);
        boolean compile = readBool(json, "compile", true);
        String createdAt = readStr(json, "createdAt");
        String lastError = readStr(json, "lastError");
        return new Entry(group, file, enabled, compile, createdAt, lastError);
    }

    private static void writeMeta(Path base, Entry e) throws IOException {
        Path metaPath = base.resolve(e.group).resolve(e.file + ".meta.json");
        StringBuilder b = new StringBuilder(160);
        b.append("{\n");
        b.append("  \"enabled\": ").append(e.enabled).append(",\n");
        b.append("  \"compile\": ").append(e.compile).append(",\n");
        b.append("  \"createdAt\": ").append(jsonStr(e.createdAt)).append(",\n");
        b.append("  \"lastError\": ").append(jsonStr(e.lastError)).append("\n");
        b.append("}\n");
        Files.writeString(metaPath, b.toString(), StandardCharsets.UTF_8);
    }

    /** Naive scalar extractor — the meta schema is closed so we never
     *  need full JSON parsing. Matches {@code "key":<value>} where
     *  value is true/false. */
    private static boolean readBool(String json, String key, boolean dflt) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return dflt;
        int colon = json.indexOf(':', i);
        if (colon < 0) return dflt;
        String tail = json.substring(colon + 1).stripLeading();
        if (tail.startsWith("true")) return true;
        if (tail.startsWith("false")) return false;
        return dflt;
    }

    /** Naive string extractor — handles {@code "key":"value"} and
     *  {@code "key":null}. Doesn't handle escapes beyond \\ and \"
     *  (sufficient for our timestamps + plain-text error messages). */
    private static String readStr(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        if (json.startsWith("null", j)) return null;
        if (json.charAt(j) != '"') return null;
        StringBuilder out = new StringBuilder();
        j++;
        while (j < json.length()) {
            char c = json.charAt(j);
            if (c == '\\' && j + 1 < json.length()) {
                char n = json.charAt(j + 1);
                if (n == '"') out.append('"');
                else if (n == '\\') out.append('\\');
                else if (n == 'n') out.append('\n');
                else out.append(n);
                j += 2;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
                j++;
            }
        }
        return null;
    }

    static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
        return b.toString();
    }

    /** Emits a single-line JSON array for the listPersisted admin
     *  command response. Keys: group, file, enabled, compile,
     *  createdAt, lastError. */
    static String toJsonArray(List<Entry> entries) {
        StringBuilder b = new StringBuilder(256);
        b.append('[');
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) b.append(',');
            b.append('{');
            b.append("\"group\":").append(jsonStr(e.group)).append(',');
            b.append("\"file\":").append(jsonStr(e.file)).append(',');
            b.append("\"enabled\":").append(e.enabled).append(',');
            b.append("\"compile\":").append(e.compile).append(',');
            b.append("\"createdAt\":").append(jsonStr(e.createdAt)).append(',');
            b.append("\"lastError\":").append(jsonStr(e.lastError));
            b.append('}');
        }
        b.append(']');
        return b.toString();
    }
}
