/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.internal.AgronaCountersService;
import com.telamin.mongoose.internal.NoOpCountersService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4: verify the {@link MonitoringSampler}'s throughput block — labels
 * map cleanly into the right buckets, rates are computed against the previous
 * tick, and the no-op counters service leaves the {@code throughput} field
 * {@code null} so the front-end falls back to JVM-only behaviour.
 */
class MonitoringSamplerThroughputTest {

    @Test
    void no_op_counters_service_leaves_throughput_null() {
        MonitoringSampler sampler = new MonitoringSampler(1000, NoOpCountersService.INSTANCE, null);
        MonitoringSampler.JvmSnapshot snapshot = sampler.tickSnapshot();
        assertNotNull(snapshot.jvm(), "JVM block always present");
        assertNull(snapshot.throughput(),
                "no-op service → throughput field is null so the UI hides the card honestly");
    }

    @Test
    void labels_are_parsed_into_the_right_buckets() {
        AgronaCountersService counters = new AgronaCountersService(64);
        counters.feedPublishCounter("fx-market-data").increment();
        counters.feedPublishCounter("rates-feed").increment();
        counters.agentEventsCounter("priceCalculator").increment();
        counters.agentIdleCyclesCounter("priceCalculator").setOrdered(42);
        counters.processorEventsCounter("priceCalc").increment();
        counters.nodeInvocationCounter("priceCalc", "FxLineHandler").increment();
        counters.queueDepthGauge("/feed/fx/subscriber/X#1").setOrdered(7);

        MonitoringSampler sampler = new MonitoringSampler(1000, counters, null);
        MonitoringSampler.JvmSnapshot snap = sampler.tickSnapshot();
        MonitoringSampler.Throughput t = snap.throughput();
        assertNotNull(t, "operational service must populate throughput");

        Map<String, MonitoringSampler.NamedRate> feeds = t.feeds().stream()
                .collect(Collectors.toMap(MonitoringSampler.NamedRate::name, x -> x));
        assertTrue(feeds.containsKey("fx-market-data"));
        assertTrue(feeds.containsKey("rates-feed"));
        assertEquals(1L, feeds.get("fx-market-data").total());

        Map<String, MonitoringSampler.GroupRate> groups = t.groups().stream()
                .collect(Collectors.toMap(MonitoringSampler.GroupRate::name, x -> x));
        assertEquals(1L, groups.get("priceCalculator").total());
        assertEquals(42L, groups.get("priceCalculator").idleCycles(),
                "idleCycles must be paired with the group's processed counter into a single GroupRate");

        Map<String, MonitoringSampler.NamedRate> procs = t.processors().stream()
                .collect(Collectors.toMap(MonitoringSampler.NamedRate::name, x -> x));
        assertTrue(procs.containsKey("priceCalc"));

        assertEquals(1, t.nodes().size());
        MonitoringSampler.NodeRate node = t.nodes().get(0);
        assertEquals("priceCalc", node.processor());
        assertEquals("FxLineHandler", node.node());

        assertEquals(1, t.queues().size());
        assertEquals("/feed/fx/subscriber/X#1", t.queues().get(0).path());
        assertEquals(7L, t.queues().get(0).depth());
    }

    @Test
    void rate_computes_against_previous_tick_delta() throws Exception {
        AgronaCountersService counters = new AgronaCountersService(64);
        MonitoringSampler sampler = new MonitoringSampler(1000, counters, null);

        // Tick 1: 100 increments — rate is 0 because there's no previous
        // snapshot to diff against.
        for (int i = 0; i < 100; i++) counters.feedPublishCounter("fx").increment();
        MonitoringSampler.JvmSnapshot s1 = sampler.tickSnapshot();
        assertEquals(100L, s1.throughput().feeds().get(0).total());
        assertEquals(0.0, s1.throughput().feeds().get(0).rate(), 0.01,
                "first tick has no prior snapshot to diff — rate is zero by contract");

        // Tick 2: another 50 increments. Sleep a measurable window so the
        // delta/window calc is non-trivial. Rate ≈ 50 / window-secs.
        Thread.sleep(50);
        for (int i = 0; i < 50; i++) counters.feedPublishCounter("fx").increment();
        MonitoringSampler.JvmSnapshot s2 = sampler.tickSnapshot();
        MonitoringSampler.NamedRate fx = s2.throughput().feeds().get(0);
        assertEquals(150L, fx.total());
        assertTrue(fx.rate() > 0, "rate should be positive after delta accumulation; got " + fx.rate());
    }

    @Test
    void counters_added_mid_life_appear_on_the_next_tick() {
        AgronaCountersService counters = new AgronaCountersService(64);
        MonitoringSampler sampler = new MonitoringSampler(1000, counters, null);

        counters.feedPublishCounter("fx").increment();
        MonitoringSampler.JvmSnapshot s1 = sampler.tickSnapshot();
        assertEquals(1, s1.throughput().feeds().size());

        counters.feedPublishCounter("rates").increment();
        counters.feedPublishCounter("trades").increment();
        MonitoringSampler.JvmSnapshot s2 = sampler.tickSnapshot();
        assertEquals(3, s2.throughput().feeds().size(),
                "new counters added since last tick should appear on the next snapshot");
    }

    @Test
    void existing_constructor_continues_to_work_for_legacy_tests() {
        // Backwards compatibility: the old (intervalMs)-only constructor must
        // still work for tests that don't care about counters. It wires the
        // no-op service internally.
        MonitoringSampler sampler = new MonitoringSampler(1000);
        MonitoringSampler.JvmSnapshot snap = sampler.tickSnapshot();
        assertNull(snap.throughput());
        assertFalse(sampler.isPaused());
    }
}
