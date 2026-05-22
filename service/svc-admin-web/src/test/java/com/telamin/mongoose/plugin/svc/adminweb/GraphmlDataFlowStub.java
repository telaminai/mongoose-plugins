/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.fluxtion.runtime.DataFlow;

/**
 * Minimal {@code DataFlow} stub used by graphml-endpoint tests. The class's
 * FQN drives the expected resource path
 * ({@code com/telamin/mongoose/plugin/svc/adminweb/GraphmlDataFlowStub.graphml}),
 * so the matching test resource lives alongside this file in
 * {@code src/test/resources/}.
 */
public class GraphmlDataFlowStub implements DataFlow {
    @Override public void onEvent(Object e) { }
}
