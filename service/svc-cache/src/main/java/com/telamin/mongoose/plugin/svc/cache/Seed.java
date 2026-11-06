/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.cache;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Seeds configuration into a {@link Cache}, only where a key is absent, so a restart keeps what is stored. Typically
 * called from {@link PersistentNode#onCache(Cache)}.
 */
public final class Seed {
    private Seed() {
    }

    /** Each property {@code k=v} of the file is stored as {@code prefix + k}, unless that key already exists. */
    public static void properties(Cache cache, String file, String prefix) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of(file))) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        p.stringPropertyNames().forEach(k -> value(cache, prefix + k, p.getProperty(k)));
    }

    /** The whole file's text under {@code key}, unless it already exists. */
    public static void file(Cache cache, String file, String key) {
        try {
            value(cache, key, Files.readString(Path.of(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** {@code value} under {@code key}, unless it already exists. */
    public static void value(Cache cache, String key, Object value) {
        if (cache.get(key) == null) {
            cache.put(key, value);
        }
    }
}
