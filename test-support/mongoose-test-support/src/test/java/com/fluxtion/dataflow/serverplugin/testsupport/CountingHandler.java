/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.testsupport;

import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;

/**
 * Test fixture for {@link DynamicProcessorRegistrationTest}. Counts String events.
 *
 * <p>Deliberately does NOT override {@code start()} to call
 * {@code getContext().subscribeToNamedFeed(...)} — the whole point of
 * {@code broadcast=true} is that the handler shouldn't need to.
 *
 * <p>Top-level (not nested in the test class) because Fluxtion's source-gen
 * embeds the class FQN into the generated Processor and mangles inner-class
 * names (the generated code references {@code Outer.Outer.Inner}).
 *
 * <p>{@code count} is a {@code volatile int} (not an {@code AtomicInteger}) so
 * Fluxtion's source-gen can construct the bean — complex-typed instance fields
 * without a matching ctor arg trip "cannot find matching constructor". The
 * dispatcher thread is the only writer; {@code volatile} gives the test thread
 * a clean read.
 *
 * <p>The test must resolve the live bean via
 * {@code flow.getNodeById("dynamicHandler")} after compile — source-gen
 * replaces the user-supplied instance with a freshly-constructed one inside
 * the generated processor.
 */
public class CountingHandler extends ObjectEventHandlerNode {
    public volatile int count;

    @Override
    protected boolean handleEvent(Object event) {
        if (event instanceof String) {
            count++;
        }
        return true;
    }
}
