/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.testsupport;

import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Documents the integration seam between Mongoose's static-processor and
 * dynamic-processor registration paths.
 *
 * <p>A statically-registered processor (via {@code MongooseServerConfig.addProcessor(...)})
 * receives events from a {@code broadcast=true} feed without any handler-side
 * code — that's the documented contract.
 *
 * <p>A dynamically-registered processor (via {@code MongooseServerController.addEventProcessor(...)}
 * called during a service's {@code start()} hook — the path that
 * {@code svc-loader-yaml} and {@code svc-loader-spring} use) should behave
 * the same way: a handler with no {@code subscribeToNamedFeed} call should
 * receive every event the static-path handler does.
 *
 * <p>Today it doesn't, and the loader examples paper over the gap by calling
 * {@code getContext().subscribeToNamedFeed(name)} in the handler's
 * {@code start()}. That extra call is invisible to anyone reading the
 * catalogue's {@code @OnEventHandler} examples — a footgun.
 *
 * <h2>TODO — unify static and dynamic processor registration</h2>
 *
 * The fix lives in Mongoose, not in this test:
 *
 * <ol>
 *   <li>When {@link MongooseServerController#addEventProcessor} is invoked at
 *       any point in the server lifecycle, the resulting processor should
 *       inherit the same broadcast-feed subscriptions a static processor
 *       would have received at boot. Today {@code AbstractEventSourceService}
 *       only registers a subscriber for broadcast on processors known to it
 *       at start-time; processors added after wiring is "frozen" never
 *       receive the broadcast.</li>
 *   <li>The {@code MongooseServer.start()} late-start pass added in 1.0.9
 *       starts the agent thread, but doesn't replay the broadcast-subscribe
 *       step for the late-arriving processor.</li>
 *   <li>Either (a) make broadcast wiring lazy/reactive so adding a processor
 *       triggers (re)wiring against all existing broadcast feeds, or
 *       (b) document {@code subscribeToNamedFeed} as a required call for
 *       dynamic processors and surface that loudly in the catalogue
 *       loader-yaml / loader-spring pages.</li>
 * </ol>
 *
 * <p>When this test passes, the loader examples can drop their {@code start()}
 * override and the catalogue's plain {@code @OnEventHandler} examples will be
 * accurate for both registration paths.
 */
class DynamicProcessorRegistrationTest {

    @Test
    void dynamic_processor_receives_broadcast_events_without_explicit_subscribe() {
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();
        CountingHandler staticHandler = new CountingHandler();
        DynamicRegistrarService registrar = new DynamicRegistrarService();

        try (MongooseTestHarness h = MongooseTestHarness.builder()
                .feed("feed", feed, "feed-agent")        // broadcast=true by default
                .processor("static-group", "static-processor", staticHandler)
                .service("dynamic-registrar", registrar)
                .start()) {

            // Give the dynamic registrar time to install its processor.
            h.awaitCondition(() -> registrar.handler.count >= 0
                    && h.server().registeredProcessors().containsKey("dynamic-group"));

            feed.offer("a");
            feed.offer("b");
            feed.offer("c");

            // Static handler receives events: this is the documented contract.
            h.awaitCondition(() -> staticHandler.count >= 3);
            assertEquals(3, staticHandler.count, "static handler should see all 3 events");

            // TODO seam: the dynamically-registered processor SHOULD receive the same
            // broadcast events as the static one. Today it doesn't, because the
            // broadcast wiring path doesn't pick up processors added after the
            // server lifecycle has progressed past EventFlowManager.start().
            //
            // Until that's fixed, the only way to make this pass is for
            // CountingHandler to override start() with
            //     getContext().subscribeToNamedFeed("feed");
            // — which means the catalogue's @OnEventHandler examples are subtly
            // wrong for any handler loaded via svc-loader-yaml / svc-loader-spring.
            assertEquals(3, registrar.handler.count,
                    "dynamic handler should also see all 3 events via broadcast=true wiring");
        }
    }
}
