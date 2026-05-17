/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.Fluxtion;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.agrona.concurrent.YieldingIdleStrategy;

/**
 * Test fixture for {@link DynamicProcessorRegistrationTest}. Registers a
 * Fluxtion processor dynamically during its own {@link #start()} hook —
 * mimics the {@code svc-loader-yaml} / {@code svc-loader-spring} pattern.
 *
 * <p>Top-level (not nested) because Mongoose's
 * {@code ServiceConfig.toService()} stores the service class as
 * {@code Class.getCanonicalName()}, which returns {@code Outer.Inner} for
 * inner classes and breaks the later {@code Class.forName(...)} lookup.
 */
public class DynamicRegistrarService implements Lifecycle {

    public volatile DataFlow flow;

    private MongooseServerController serverController;

    @ServiceRegistered
    public void wire(MongooseServerController controller, String name) {
        this.serverController = controller;
    }

    @Override
    public void init() {
    }

    @Override
    public void start() {
        CountingHandler handler = new CountingHandler();
        DataFlow compiled = Fluxtion.compile(cfg -> cfg.addNode(handler, "dynamicHandler"));
        compiled.init();
        this.flow = compiled;
        serverController.addEventProcessor(
                "dynamic-processor",
                "dynamic-group",
                new YieldingIdleStrategy(),
                () -> compiled);
    }

    @Override
    public void tearDown() {
    }
}
