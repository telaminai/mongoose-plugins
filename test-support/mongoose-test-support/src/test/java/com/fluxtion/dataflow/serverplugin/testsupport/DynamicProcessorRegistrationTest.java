/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.testsupport;

import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Documents the Fluxtion-compile + dynamic-registration seam.
 *
 * <p>The Mongoose dispatcher itself is symmetric for static vs dynamic
 * registration — see {@code mongoose/src/test/.../DynamicProcessorRegistrationTest}
 * in the mongoose repo, which uses a hand-rolled {@code DataFlow} that
 * subscribes to its feed from {@code start()} and passes for both paths.
 *
 * <p>The seam this test exercises lives one layer up. When the handler is an
 * {@code ObjectEventHandlerNode} compiled into a {@code DataFlow} by
 * {@code Fluxtion.compile(...)}, the generated processor relies on
 * compile-time auto-subscription from the {@code @OnEventHandler}
 * annotation. That auto-subscription wires correctly when the processor is
 * known to the {@code EventFlowManager} during its static-wire phase, but
 * does not re-fire when the processor is added later via
 * {@code MongooseServerController.addEventProcessor(...)} from a service's
 * {@code start()} hook — the path used by {@code svc-loader-yaml} and
 * {@code svc-loader-spring}.
 *
 * <p>The two loader-example handlers paper over the gap by overriding
 * {@code start()} to call {@code getContext().subscribeToNamedFeed(name)}.
 * That extra call is invisible to anyone copying the catalogue's plain
 * {@code @OnEventHandler} examples — a footgun for adapters.
 *
 * <h2>TODO — auto-subscribe Fluxtion-compiled processors on dynamic registration</h2>
 *
 * The fix most likely lives in fluxtion-builder rather than mongoose:
 *
 * <ol>
 *   <li>When {@code ComposingEventProcessorAgent.checkForAdded()} calls
 *       {@code eventProcessor.addEventFeed(this)} on a Fluxtion-compiled
 *       processor, that processor should walk its {@code @OnEventHandler}
 *       bindings and call {@code feed.subscribe(this, key)} for each one —
 *       not just at compile-time-bound feeds, but at the live feeds it sees
 *       through {@code addEventFeed}.</li>
 *   <li>Equivalently: emit a {@code start()} body in the generated processor
 *       that re-subscribes to feed names referenced by {@code @OnEventHandler}
 *       annotations.</li>
 * </ol>
 *
 * <p>When this test passes, the loader-yaml / loader-spring example handlers
 * can drop their {@code start()} override and the catalogue's plain
 * {@code @OnEventHandler} examples will be accurate for both registration
 * paths.
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

            // TODO seam: a Fluxtion-compiled @OnEventHandler that is registered
            // dynamically (via MongooseServerController.addEventProcessor from a
            // service's start()) does not pick up its feed subscriptions. The
            // same handler registered statically would. Today this assertion
            // fails: dynamic handler sees 0 events.
            //
            // The fix lives in fluxtion-builder's generated processor — its
            // addEventFeed(EventFeed) / start() should re-subscribe against the
            // feeds named in its @OnEventHandler annotations. Until that ships,
            // dynamically-loaded handlers must override start() with
            //     getContext().subscribeToNamedFeed("feed");
            // which is what the loader-yaml / loader-spring examples do — and
            // which contradicts the catalogue's plain @OnEventHandler examples.
            assertEquals(3, registrar.handler.count,
                    "dynamic handler should also see all 3 events via @OnEventHandler auto-subscribe");
        }
    }
}
