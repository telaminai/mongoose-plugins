/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

/**
 * Child process for {@link JsonFileCacheKillTest}: reopens the cache file, checks the previous run's last value is
 * whole, then writes large values in a loop until it is killed.
 */
public final class JsonFileCacheKillWriter {

    static final int PAYLOAD = 400_000;

    public static void main(String[] args) throws Exception {
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(args[0]);
        cache.init();
        String last = cache.get("blob");
        long start = last == null ? 0 : Long.parseLong(last.substring(0, last.indexOf(':'))) + 1;
        for (long i = start; ; i++) {
            cache.put("blob", i + ":" + Long.toString(i % 10).repeat(PAYLOAD));
            if (i == start) {
                System.out.println("writing");
                System.out.flush();
            }
        }
    }
}
