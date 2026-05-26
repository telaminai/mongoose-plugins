/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.loader.sink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Persistence-store contract — round-trip, enable/disable, remove,
 *  filename traversal, meta survival. The store is the one place we
 *  validate operator-supplied group + filename strings before they
 *  hit the filesystem, so traversal-rejection coverage is critical. */
class PersistedConfigStoreTest {

    @Test
    void persist_then_list_round_trip(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("incoming.yaml");
        Files.writeString(src, "key: value\n");
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());

        PersistedConfigStore.Entry persisted = PersistedConfigStore.persist(base, "g1", src, true);

        assertEquals("g1", persisted.group);
        assertEquals("incoming.yaml", persisted.file);
        assertTrue(persisted.enabled);
        assertTrue(persisted.compile);
        assertNotNull(persisted.createdAt);

        List<PersistedConfigStore.Entry> listed = PersistedConfigStore.list(base);
        assertEquals(1, listed.size());
        assertEquals("incoming.yaml", listed.get(0).file);
    }

    @Test
    void persist_overwrites_previous(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("v.yaml");
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());
        Files.writeString(src, "first\n");
        PersistedConfigStore.persist(base, "g", src, true);
        Files.writeString(src, "second\n");
        PersistedConfigStore.persist(base, "g", src, false);

        List<PersistedConfigStore.Entry> entries = PersistedConfigStore.list(base);
        assertEquals(1, entries.size());
        assertFalse(entries.get(0).compile);
        assertEquals("second\n", PersistedConfigStore.readSource(base, "g", "v.yaml"));
    }

    @Test
    void set_enabled_toggle(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("v.yaml"); Files.writeString(src, "x\n");
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());
        PersistedConfigStore.persist(base, "g", src, true);

        PersistedConfigStore.setEnabled(base, "g", "v.yaml", false);
        assertFalse(PersistedConfigStore.list(base).get(0).enabled);

        PersistedConfigStore.setEnabled(base, "g", "v.yaml", true);
        assertTrue(PersistedConfigStore.list(base).get(0).enabled);
    }

    @Test
    void remove_deletes_source_meta_and_empty_group_dir(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("v.yaml"); Files.writeString(src, "x\n");
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());
        PersistedConfigStore.persist(base, "g", src, true);

        PersistedConfigStore.remove(base, "g", "v.yaml");

        assertFalse(Files.exists(base.resolve("g/v.yaml")));
        assertFalse(Files.exists(base.resolve("g/v.yaml.meta.json")));
        // empty group dir cleaned up
        assertFalse(Files.exists(base.resolve("g")));
    }

    @Test
    void traversal_in_group_rejected(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("v.yaml"); Files.writeString(src, "x\n");
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());

        assertThrows(IllegalArgumentException.class,
                () -> PersistedConfigStore.persist(base, "../escape", src, true));
        assertThrows(IllegalArgumentException.class,
                () -> PersistedConfigStore.setEnabled(base, "../escape", "v.yaml", false));
        assertThrows(IllegalArgumentException.class,
                () -> PersistedConfigStore.remove(base, "g/sub", "v.yaml"));
    }

    @Test
    void traversal_in_file_rejected(@TempDir Path tmp) throws Exception {
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());
        assertThrows(IllegalArgumentException.class,
                () -> PersistedConfigStore.readSource(base, "g", "../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> PersistedConfigStore.remove(base, "g", "sub/file.yaml"));
    }

    @Test
    void list_handles_missing_meta_gracefully(@TempDir Path tmp) throws Exception {
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());
        Path groupDir = base.resolve("g");
        Files.createDirectories(groupDir);
        Files.writeString(groupDir.resolve("hand-placed.yaml"), "x\n");

        List<PersistedConfigStore.Entry> entries = PersistedConfigStore.list(base);
        assertEquals(1, entries.size());
        assertEquals("hand-placed.yaml", entries.get(0).file);
        assertTrue(entries.get(0).enabled, "missing meta defaults to enabled");
        assertNotNull(entries.get(0).lastError, "missing meta surfaces as a non-fatal lastError");
    }

    @Test
    void update_last_error_round_trip(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("v.yaml"); Files.writeString(src, "x\n");
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());
        PersistedConfigStore.persist(base, "g", src, true);

        PersistedConfigStore.updateLastError(base, "g", "v.yaml", "boom: line 42");
        assertEquals("boom: line 42", PersistedConfigStore.list(base).get(0).lastError);

        PersistedConfigStore.updateLastError(base, "g", "v.yaml", null);
        assertNull(PersistedConfigStore.list(base).get(0).lastError);
    }

    @Test
    void to_json_array_emits_valid_shape(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("v.yaml"); Files.writeString(src, "x\n");
        Path base = PersistedConfigStore.resolveBaseDir(tmp.resolve("state").toString());
        PersistedConfigStore.persist(base, "g", src, true);
        PersistedConfigStore.updateLastError(base, "g", "v.yaml", "with \"quotes\" and\nnewline");

        String json = PersistedConfigStore.toJsonArray(PersistedConfigStore.list(base));

        assertTrue(json.startsWith("[{"));
        assertTrue(json.endsWith("}]"));
        assertTrue(json.contains("\"group\":\"g\""));
        assertTrue(json.contains("\"file\":\"v.yaml\""));
        assertTrue(json.contains("\"enabled\":true"));
        // String escapes preserved
        assertTrue(json.contains("\\\"quotes\\\""));
        assertTrue(json.contains("\\n"));
    }

    @Test
    void blank_base_dir_returns_null(@TempDir Path tmp) throws Exception {
        assertNull(PersistedConfigStore.resolveBaseDir(null));
        assertNull(PersistedConfigStore.resolveBaseDir(""));
        assertNull(PersistedConfigStore.resolveBaseDir("   "));
    }
}
