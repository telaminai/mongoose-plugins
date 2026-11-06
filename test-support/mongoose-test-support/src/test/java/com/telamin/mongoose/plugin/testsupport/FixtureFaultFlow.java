/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.plugin.svc.cache.JsonFileCache;

import java.util.ArrayList;
import java.util.List;

/** A processor for {@link FixtureRunnerFaultTest} that fails where told and records its lifecycle calls. */
public class FixtureFaultFlow implements DataFlow {

    static final List<String> CALLS = new ArrayList<>();
    static boolean failStop;

    /** Fails whole-map serialisation, so the cache's tearDown write throws. */
    public static class Unwritable extends JsonFileCache.TypedData {
        public String getBoom() {
            throw new IllegalStateException("cache write failed");
        }
    }

    private JsonFileCache cache;

    @Override
    public void registerService(Service<?> service) {
        if (service.instance() instanceof JsonFileCache c) {
            cache = c;
        }
    }

    @Override
    public void onEvent(Object event) {
        if ("POISON".equals(event)) {
            cache.getCacheMap().put("unwritable", new Unwritable());
            throw new IllegalStateException("original failure");
        }
    }

    @Override
    public void stop() {
        CALLS.add("stop");
        if (failStop) {
            throw new IllegalStateException("stop failed");
        }
    }

    @Override
    public void tearDown() {
        CALLS.add("tearDown");
    }

    @Override
    public <T> T getNodeById(String id) throws NoSuchFieldException {
        throw new NoSuchFieldException(id);
    }
}
