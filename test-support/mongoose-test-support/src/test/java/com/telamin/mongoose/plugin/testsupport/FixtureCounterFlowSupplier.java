/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.runtime.DataFlow;

import java.util.function.Supplier;

/** {@code --processor} may name a Supplier of the processor. */
public class FixtureCounterFlowSupplier implements Supplier<DataFlow> {
    @Override
    public DataFlow get() {
        return new FixtureCounterFlow();
    }
}
