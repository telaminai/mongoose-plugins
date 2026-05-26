/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.micrometer;

import com.telamin.mongoose.service.counters.MongooseCountersService;
import com.telamin.mongoose.service.counters.MongooseLatencyService;
import com.telamin.mongoose.service.counters.NodeLatencySnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MicrometerBridge} against {@link SimpleMeterRegistry}
 * and a hand-rolled counters/latency stub. Pins:
 *  - first-sight counter registration creates a Gauge tied to a backing AtomicLong
 *  - subsequent sampling pushes new values into the SAME Gauge (no leak)
 *  - latency snapshot registers 5 Gauges per (processor, node) row
 *  - non-operational counters service is silently skipped (no meters created)
 *  - meter-name prefix is applied
 */
class MicrometerBridgeTest {

    /** Tiny in-memory counters stub — lets tests drive the visitor with
     *  arbitrary (label → value) pairs. */
    private static final class StubCounters implements MongooseCountersService {
        boolean operational = true;
        final List<long[]> entries = new ArrayList<>(); // [id, value]
        final List<String> labels = new ArrayList<>();

        void set(String label, long value) {
            int idx = labels.indexOf(label);
            if (idx < 0) {
                labels.add(label);
                entries.add(new long[]{ entries.size(), value });
            } else {
                entries.get(idx)[1] = value;
            }
        }

        // Counter-factory methods — not exercised by the bridge tests
        // (the bridge only consumes forEachCounter snapshots).
        @Override public com.telamin.mongoose.service.counters.MongooseCounter counter(String label) {
            throw new UnsupportedOperationException("not needed for tests");
        }
        @Override public com.telamin.mongoose.service.counters.MongooseCounter feedPublishCounter(String feed) { throw new UnsupportedOperationException(); }
        @Override public com.telamin.mongoose.service.counters.MongooseCounter agentEventsCounter(String group) { throw new UnsupportedOperationException(); }
        @Override public com.telamin.mongoose.service.counters.MongooseCounter agentIdleCyclesCounter(String group) { throw new UnsupportedOperationException(); }
        @Override public com.telamin.mongoose.service.counters.MongooseCounter queueDepthGauge(String path) { throw new UnsupportedOperationException(); }
        @Override public com.telamin.mongoose.service.counters.MongooseCounter processorEventsCounter(String processor) { throw new UnsupportedOperationException(); }
        @Override public com.telamin.mongoose.service.counters.MongooseCounter nodeInvocationCounter(String processor, String node) { throw new UnsupportedOperationException(); }
        @Override public void forEachCounter(CounterVisitor visitor) {
            for (int i = 0; i < labels.size(); i++) {
                long[] e = entries.get(i);
                visitor.visit((int) e[0], labels.get(i), e[1]);
            }
        }
        @Override public boolean isOperational() { return operational; }
    }

    /** Tiny latency stub. */
    private static final class StubLatency implements MongooseLatencyService {
        final List<Object[]> rows = new ArrayList<>(); // [processor, node, snapshot]
        void set(String processor, String node, NodeLatencySnapshot s) {
            for (Object[] r : rows) {
                if (r[0].equals(processor) && r[1].equals(node)) { r[2] = s; return; }
            }
            rows.add(new Object[]{ processor, node, s });
        }
        @Override public void forEachNode(LatencyVisitor visitor) {
            for (Object[] r : rows) {
                visitor.visit((String) r[0], (String) r[1], (NodeLatencySnapshot) r[2]);
            }
        }
        @Override public void recordNodeLatency(String processor, String node, long nanos) {
            // tests drive snapshots directly via set(); recording path
            // not exercised here.
        }
    }

    @Test
    void counter_registers_as_gauge_on_first_sight_with_default_prefix() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubCounters counters = new StubCounters();
        counters.set("feed.prices.published", 42);

        MicrometerBridge bridge = new MicrometerBridge();
        bridge.setRegistryForTest(registry);
        bridge.setCountersServiceForTest(counters);
        bridge.sampleNow();

        Gauge g = registry.find("mongoose.feed.prices.published").gauge();
        assertNotNull(g, "Gauge should be registered after first sample");
        assertEquals(42.0, g.value(), 0.0);
        assertEquals(1, bridge.counterMetersSize());
    }

    @Test
    void counter_second_sample_updates_existing_gauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubCounters counters = new StubCounters();
        counters.set("app.trade.count", 1);

        MicrometerBridge bridge = new MicrometerBridge();
        bridge.setRegistryForTest(registry);
        bridge.setCountersServiceForTest(counters);
        bridge.sampleNow();
        assertEquals(1.0, registry.find("mongoose.app.trade.count").gauge().value(), 0.0);

        // Increment — bridge must update the SAME backing AtomicLong, not
        // register a new meter.
        counters.set("app.trade.count", 7);
        bridge.sampleNow();
        assertEquals(7.0, registry.find("mongoose.app.trade.count").gauge().value(), 0.0);
        assertEquals(1, bridge.counterMetersSize(), "no duplicate meter for the same label");
    }

    @Test
    void non_operational_counters_service_is_skipped() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubCounters counters = new StubCounters();
        counters.operational = false;
        counters.set("feed.x.published", 99);

        MicrometerBridge bridge = new MicrometerBridge();
        bridge.setRegistryForTest(registry);
        bridge.setCountersServiceForTest(counters);
        bridge.sampleNow();

        assertEquals(0, bridge.counterMetersSize());
        assertEquals(0, registry.getMeters().size());
    }

    @Test
    void latency_row_registers_five_tagged_gauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubLatency latency = new StubLatency();
        latency.set("pnl-processor", "pnlSummaryCalc",
                new NodeLatencySnapshot(100, 10, 25, 50, 75, 100));

        MicrometerBridge bridge = new MicrometerBridge();
        bridge.setRegistryForTest(registry);
        bridge.setLatencyServiceForTest(latency);
        bridge.sampleNow();

        // Five gauges per row: count, p50, p99, p999, max.
        long matching = registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("mongoose.latency."))
                .count();
        assertEquals(5, matching);
        assertEquals(1, bridge.latencyRowsSize());

        Gauge p99 = registry.find("mongoose.latency.p99")
                .tag("processor", "pnl-processor")
                .tag("node", "pnlSummaryCalc")
                .gauge();
        assertNotNull(p99, "p99 gauge must carry the processor/node tags");
        assertEquals(50.0, p99.value(), 0.0);
    }

    @Test
    void latency_updates_existing_gauges_in_place() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubLatency latency = new StubLatency();
        latency.set("pnl-processor", "node1",
                new NodeLatencySnapshot(10, 5, 7, 9, 10, 12));

        MicrometerBridge bridge = new MicrometerBridge();
        bridge.setRegistryForTest(registry);
        bridge.setLatencyServiceForTest(latency);
        bridge.sampleNow();
        bridge.sampleNow(); // second pass with same data — must not duplicate

        latency.set("pnl-processor", "node1",
                new NodeLatencySnapshot(20, 6, 8, 11, 13, 14));
        bridge.sampleNow();

        assertEquals(5, registry.getMeters().size(), "still 5 gauges, no duplicates");
        assertEquals(14.0, registry.find("mongoose.latency.max")
                .tag("processor", "pnl-processor")
                .tag("node", "node1").gauge().value(), 0.0);
    }

    @Test
    void counterPrefix_can_be_overridden() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubCounters counters = new StubCounters();
        counters.set("feed.f.published", 1);

        MicrometerBridge bridge = new MicrometerBridge();
        bridge.setCounterPrefix("svc");
        bridge.setRegistryForTest(registry);
        bridge.setCountersServiceForTest(counters);
        bridge.sampleNow();

        assertNotNull(registry.find("svc.feed.f.published").gauge());
        assertTrue(registry.find("mongoose.feed.f.published").gauges().isEmpty());
    }
}
