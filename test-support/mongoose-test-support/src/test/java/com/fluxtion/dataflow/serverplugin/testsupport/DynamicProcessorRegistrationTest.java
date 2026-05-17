/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.testsupport;

import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down the broadcast=true contract for a Fluxtion-compiled handler
 * registered dynamically — installed via
 * {@code MongooseServerController.addEventProcessor(...)} from a service's
 * {@code start()} hook, the path used by {@code svc-loader-yaml} and
 * {@code svc-loader-spring}.
 *
 * <p>An {@code ObjectEventHandlerNode} compiled into a {@code DataFlow} by
 * {@code Fluxtion.compile(...)} and added dynamically receives every event
 * a {@code broadcast=true} feed publishes. The wiring runs through the
 * existing {@link com.telamin.fluxtion.runtime.input.SubscriptionManagerNode}
 * paths: during the dispatcher's {@code checkForAdded()} pass,
 * {@code registerService(broadcastFeed)} drives the broadcast feed's
 * {@code registerSubscriber} which calls {@code subscriptionManager.subscribe(key)},
 * and the subsequent {@code addEventFeed(agent)} propagates that subscription
 * from {@code subscriptionMap} to the agent. No explicit
 * {@code subscribeToNamedFeed(name)} is required.
 *
 * <p>Two traps make this look like a framework bug when it isn't:
 * <ol>
 *   <li><b>Wrong instance.</b> Fluxtion source-gen constructs its own bean
 *       inside the generated processor; the reference handed to
 *       {@code cfg.addNode(...)} is not the receiver. Read state from the
 *       live bean via {@code flow.getNodeById(id)}.</li>
 *   <li><b>Wrong wait condition.</b>
 *       {@code MongooseServer.registeredProcessors().containsKey(group)}
 *       is true as soon as the agent group exists, before the dispatcher
 *       has drained the queued reader and started polling. Wait for the
 *       group's {@code registeredEventProcessors()} to be non-empty before
 *       offering events.</li>
 * </ol>
 *
 * <p>Companion test:
 * {@code mongoose/src/test/.../DynamicProcessorRegistrationTest} pins the
 * same contract one layer down, using a hand-rolled {@code DataFlow} that
 * subscribes from its own {@code start()}.
 *
 * <p>Implication for the catalogue: the {@code start()} override that calls
 * {@code getContext().subscribeToNamedFeed(name)} in the
 * {@code yaml-service-loader-example} and {@code spring-service-loader-example}
 * handlers is redundant for {@code broadcast=true} feeds. The catalogue's
 * plain {@code @OnEventHandler} examples are accurate for both registration
 * paths.
 */
class DynamicProcessorRegistrationTest {

    @Test
    void dynamic_processor_receives_broadcast_events_without_explicit_subscribe() throws NoSuchFieldException {
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();
        CountingHandler staticHandler = new CountingHandler();
        DynamicRegistrarService registrar = new DynamicRegistrarService();

        try (MongooseTestHarness h = MongooseTestHarness.builder()
                .feed("feed", feed, "feed-agent")        // broadcast=true by default
                .processor("static-group", "static-processor", staticHandler)
                .service("dynamic-registrar", registrar)
                .start()) {

            // Give the dynamic registrar time to install its processor AND
            // for the dispatcher to drain the queued reader so events flow.
            h.awaitCondition(() -> registrar.flow != null
                    && h.server().registeredProcessors().getOrDefault("dynamic-group",
                            java.util.Collections.emptyList()).stream().findAny().isPresent());

            // Resolve the LIVE handler bean. Fluxtion source-gen replaces the
            // user-supplied node instance with a new bean inside the generated
            // processor — reading the original reference would always show 0.
            CountingHandler liveDynamicHandler = (CountingHandler)
                    registrar.flow.getNodeById("dynamicHandler");

            feed.offer("a");
            feed.offer("b");
            feed.offer("c");

            // Static handler receives events: this is the documented contract.
            h.awaitCondition(() -> staticHandler.count >= 3);
            assertEquals(3, staticHandler.count, "static handler should see all 3 events");

            // Dynamic path: same broadcast contract holds without an explicit
            // subscribeToNamedFeed call — the @OnEventHandler annotation is
            // sufficient. The loader-yaml / loader-spring example handlers'
            // start() override is therefore redundant for broadcast=true feeds.
            h.awaitCondition(() -> liveDynamicHandler.count >= 3);
            assertEquals(3, liveDynamicHandler.count,
                    "dynamic handler should also see all 3 events via @OnEventHandler auto-subscribe");
        }
    }
}
