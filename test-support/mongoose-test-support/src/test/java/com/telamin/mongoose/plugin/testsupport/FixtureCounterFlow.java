/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.Signal;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.plugin.svc.cache.Cache;
import com.telamin.mongoose.service.admin.AdminCommandRequest;

import java.util.Map;

/**
 * A hand-written processor for {@link FixtureRunnerTest}, standing in for a generated one: counts String events in a
 * Cache (so a restart resumes the count), publishes a line and a record per event, answers admin command "count".
 */
public class FixtureCounterFlow implements DataFlow {

    public record Counted(String value, int count) {
    }

    private Cache cache;
    private MessageSink<Object> sink;
    private int count;

    @Override
    @SuppressWarnings("unchecked")
    public void registerService(Service<?> service) {
        if (service.instance() instanceof Cache c && "counterState".equals(service.serviceName())) {
            cache = c;
            count = c.getOrDefault("count", 0);
        } else if (service.instance() instanceof MessageSink<?> s) {
            sink = (MessageSink<Object>) s;
        }
    }

    @Override
    public void onEvent(Object event) {
        if (event instanceof Signal<?> signal && signal.getValue() instanceof AdminCommandRequest request) {
            if (signal.filterString().equals("admin:count")) {
                request.getOutput().accept("count=" + count);
            }
            return;
        }
        if ("BOOM".equals(event)) {
            throw new IllegalStateException("bad event");
        }
        if (event instanceof String s) {
            count++;
            cache.put("count", count);
            sink.accept("seen " + s);
            sink.accept(new Counted(s, count));
        } else if (event instanceof Integer i) {
            sink.accept(Map.of("other", i));
        }   // anything else, e.g. the audit control event: ignored
    }

    @Override
    public <T> T getNodeById(String id) throws NoSuchFieldException {
        throw new NoSuchFieldException(id);
    }
}
