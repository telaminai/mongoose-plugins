/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SeedTest {

    @TempDir
    Path tempDir;

    @Test
    void properties_seed_absent_keys_only() throws Exception {
        Path props = tempDir.resolve("x.properties");
        Files.writeString(props, "limit=5\nname=shop\n");
        InMemoryCache cache = new InMemoryCache();
        cache.put("x.limit", "9");
        Seed.properties(cache, props.toString(), "x.");
        assertEquals("9", cache.get("x.limit"), "a stored value is kept");
        assertEquals("shop", cache.get("x.name"));
    }

    @Test
    void file_seeds_the_whole_text() throws Exception {
        Path rates = tempDir.resolve("rates.csv");
        Files.writeString(rates, "a,1\nb,2\n");
        InMemoryCache cache = new InMemoryCache();
        Seed.file(cache, rates.toString(), "rates");
        assertEquals("a,1\nb,2\n", cache.get("rates"));
        Files.writeString(rates, "changed");
        Seed.file(cache, rates.toString(), "rates");
        assertEquals("a,1\nb,2\n", cache.get("rates"));
    }

    @Test
    void a_missing_file_fails_clearly() {
        InMemoryCache cache = new InMemoryCache();
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> Seed.properties(cache, tempDir.resolve("none").toString(), "p."));
        assertTrue(e.getMessage().contains("none"));
    }
}
