/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import java.util.function.Function;

/** A feed mapper for {@link FixtureRunnerTest}: upper-cases lines. */
public class FixtureUpperMapper implements Function<Object, Object> {
    @Override
    public Object apply(Object o) {
        return o instanceof String s ? s.toUpperCase() : o;
    }
}
