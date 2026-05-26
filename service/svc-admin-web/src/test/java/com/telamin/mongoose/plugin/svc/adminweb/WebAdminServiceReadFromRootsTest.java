/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers the filesystem fallback used by the graphml endpoint when
 *  the runtime classloader has no resource — the yaml + spring runtime
 *  loaders depend on this path to make their freshly-generated
 *  .graphml visible to the admin UI. */
class WebAdminServiceReadFromRootsTest {

    @Test
    void finds_file_under_root(@TempDir Path tmp) throws Exception {
        Path nested = tmp.resolve("com/example/Foo.graphml");
        Files.createDirectories(nested.getParent());
        byte[] payload = "<graphml/>".getBytes();
        Files.write(nested, payload);

        byte[] read = WebAdminService.readFromRoots(
                List.of(tmp.toString()),
                "com/example/Foo.graphml");

        assertArrayEquals(payload, read);
    }

    @Test
    void returns_null_when_missing(@TempDir Path tmp) {
        assertNull(WebAdminService.readFromRoots(
                List.of(tmp.toString()),
                "nope/notfound.graphml"));
    }

    @Test
    void returns_null_for_empty_or_null_roots() {
        assertNull(WebAdminService.readFromRoots(null, "any.graphml"));
        assertNull(WebAdminService.readFromRoots(List.of(), "any.graphml"));
    }

    @Test
    void first_hit_wins(@TempDir Path tmp) throws Exception {
        Path firstRoot = tmp.resolve("a"); Files.createDirectories(firstRoot);
        Path secondRoot = tmp.resolve("b"); Files.createDirectories(secondRoot);
        Files.createDirectories(firstRoot.resolve("p"));
        Files.createDirectories(secondRoot.resolve("p"));
        Files.write(firstRoot.resolve("p/X.graphml"),  "FROM_A".getBytes());
        Files.write(secondRoot.resolve("p/X.graphml"), "FROM_B".getBytes());

        byte[] read = WebAdminService.readFromRoots(
                List.of(firstRoot.toString(), secondRoot.toString()),
                "p/X.graphml");

        assertArrayEquals("FROM_A".getBytes(), read);
    }

    @Test
    void skips_missing_root_and_tries_next(@TempDir Path tmp) throws Exception {
        Path real = tmp.resolve("real");
        Files.createDirectories(real.resolve("p"));
        Files.write(real.resolve("p/X.graphml"), "OK".getBytes());

        byte[] read = WebAdminService.readFromRoots(
                List.of(tmp.resolve("does-not-exist").toString(), real.toString()),
                "p/X.graphml");

        assertArrayEquals("OK".getBytes(), read);
    }

    @Test
    void rejects_symlink_escape(@TempDir Path tmp) throws Exception {
        // Set up: /tmp/secret/leak.graphml (outside the root)
        //         /tmp/root/  (the root we'll search)
        //         /tmp/root/escape -> ../secret/leak.graphml  (symlink out)
        Path secret = tmp.resolve("secret");
        Files.createDirectories(secret);
        Files.write(secret.resolve("leak.graphml"), "SECRET".getBytes());

        Path root = tmp.resolve("root");
        Files.createDirectories(root);
        try {
            Files.createSymbolicLink(
                    root.resolve("escape.graphml"),
                    secret.resolve("leak.graphml"));
        } catch (UnsupportedOperationException unsupported) {
            // Skip on filesystems that don't support symlinks
            return;
        }

        // toRealPath() resolves the symlink — candidate.startsWith(root)
        // is then false, so the fallback must reject it.
        byte[] read = WebAdminService.readFromRoots(
                List.of(root.toString()),
                "escape.graphml");

        assertNull(read);
    }
}
