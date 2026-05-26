/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.micrometer;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import com.telamin.mongoose.service.counters.MongooseLatencyService;
import com.telamin.mongoose.service.counters.NodeLatencySnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer bridge for Mongoose's counters + latency services. Snapshots
 * {@link MongooseCountersService#forEachCounter} and
 * {@link MongooseLatencyService#forEachNode} into Micrometer meters once
 * per second so any Micrometer backend (Prometheus, Datadog, StatsD, OTLP,
 * CloudWatch, …) sees Mongoose runtime metrics alongside the rest of the
 * JVM's app + infra metrics.
 *
 * <h2>Wiring (YAML)</h2>
 * <pre>
 * services:
 *   - name: micrometerBridge
 *     service: !!com.telamin.mongoose.plugin.svc.micrometer.MicrometerBridge
 *       sampleIntervalMs: 1000   # snapshot cadence, default 1000
 *       counterPrefix: mongoose  # all counter meter names prefixed with this
 *       latencyPrefix: mongoose.latency
 * </pre>
 *
 * <p>The bridge picks up a {@link MeterRegistry} via {@code @ServiceRegistered}
 * — Spring Boot autoconfig / Quarkus / any container that exposes one will
 * have it injected automatically. Standalone Mongoose deployments without
 * a container can register one manually:
 * <pre>
 * services:
 *   - name: meterRegistry
 *     serviceClass: io.micrometer.core.instrument.MeterRegistry
 *     service: !!io.micrometer.core.instrument.simple.SimpleMeterRegistry {}
 * </pre>
 *
 * <h2>Name + tag mapping</h2>
 * Counter labels are flat dot-separated strings (e.g.
 * {@code feed.prices.published}, {@code processor.pnl-processor.invocations},
 * {@code node.pnl-processor.pnlSummaryCalc_3.invocations}). The bridge
 * passes them through verbatim, prefixed with {@code counterPrefix}, so
 * Prometheus's name munging produces predictable identifiers:
 * <pre>
 *   feed.prices.published                           → mongoose_feed_prices_published
 *   processor.pnl-processor.invocations             → mongoose_processor_pnl_processor_invocations
 * </pre>
 * Tag extraction (e.g. {@code processor=pnl-processor}) is a future
 * refinement — V1 prioritises predictable name passthrough over tag
 * cardinality control.
 *
 * <p>Latency snapshots register as four gauges per (processor, node):
 * {@code .p50}, {@code .p99}, {@code .p999}, {@code .max} — all in the
 * Mongoose latency clock units (nanos by default). The sample count is
 * a fifth gauge.
 *
 * <h2>Hot-path cost</h2>
 * Once per {@code sampleIntervalMs} the bridge walks every counter +
 * histogram and writes their current values into {@link AtomicLong}s
 * Micrometer's gauges read from. The hot path (counter increment, latency
 * record) is untouched. The bridge's own sampling runs on a dedicated
 * single-thread scheduler — never on a Mongoose agent.
 */
@Log4j2
public class MicrometerBridge implements Lifecycle {

    /** Snapshot cadence in milliseconds. Default 1000 — matches the
     *  svc-admin-web monitor cadence so the same data appears in both
     *  surfaces at the same beat. Lower values cost more CPU; higher
     *  values blur fast spikes. */
    @Getter @Setter private long sampleIntervalMs = 1000;

    /** Meter name prefix for counters. Default {@code "mongoose"} — keeps
     *  Mongoose-emitted metrics namespaced so they don't collide with app
     *  metrics in the same registry. */
    @Getter @Setter private String counterPrefix = "mongoose";

    /** Meter name prefix for latency gauges. Default {@code "mongoose.latency"}. */
    @Getter @Setter private String latencyPrefix = "mongoose.latency";

    // ── injected ──
    private MongooseCountersService countersService;
    private MongooseLatencyService latencyService;
    private MeterRegistry registry;

    // ── runtime state ──
    /** Per-counter backing storage. Map keyed by Mongoose counter label;
     *  the AtomicLong is the value Micrometer's Gauge reads on scrape. */
    private final Map<String, AtomicLong> counterValues = new HashMap<>();
    /** Per-(processor, node) latency snapshots; backing storage for the
     *  p50/p99/p999/max/count gauges. */
    private final Map<String, NodeLatencyHolder> latencyValues = new HashMap<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> sampleFuture;

    @ServiceRegistered
    public void countersService(MongooseCountersService svc, String name) {
        log.info("Counters service injected: '{}' operational={}", name, svc.isOperational());
        this.countersService = svc;
    }

    @ServiceRegistered
    public void latencyService(MongooseLatencyService svc, String name) {
        log.info("Latency service injected: '{}'", name);
        this.latencyService = svc;
    }

    @ServiceRegistered
    public void meterRegistry(MeterRegistry registry, String name) {
        log.info("MeterRegistry injected: '{}' impl={}", name, registry.getClass().getName());
        this.registry = registry;
    }

    @Override
    public void init() {
        // Fallback: if no MeterRegistry was supplied via service injection,
        // use a SimpleMeterRegistry so the bridge still runs (gauges are
        // queryable in-process; just nothing's exporting them off-host).
        // Operators in real deployments wire a backend-specific registry.
        if (registry == null) {
            log.info("No MeterRegistry injected — falling back to SimpleMeterRegistry "
                    + "(in-process only; install a backend registry for real export)");
            registry = new SimpleMeterRegistry();
        }
    }

    @Override
    public void start() {
        if (sampleIntervalMs <= 0) {
            log.warn("sampleIntervalMs={} — clamping to 250ms minimum", sampleIntervalMs);
            sampleIntervalMs = 250;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mongoose-micrometer-bridge");
            t.setDaemon(true);
            return t;
        });
        sampleFuture = scheduler.scheduleAtFixedRate(
                this::sampleSafely, sampleIntervalMs, sampleIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Micrometer bridge started; sampling every {} ms", sampleIntervalMs);
    }

    @Override
    public void tearDown() {
        if (sampleFuture != null) {
            sampleFuture.cancel(false);
            sampleFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Single sampling pass: walks counters + latency snapshots and
     *  pushes their values into the backing AtomicLongs. Registers a new
     *  Gauge on first sight of any (counter, latency-pair). */
    void sample() {
        if (countersService != null && countersService.isOperational()) {
            countersService.forEachCounter((id, label, value) -> {
                AtomicLong holder = counterValues.get(label);
                if (holder == null) {
                    holder = new AtomicLong(value);
                    counterValues.put(label, holder);
                    final AtomicLong ref = holder;
                    Gauge.builder(meterNameForCounter(label), ref, AtomicLong::doubleValue)
                            .description("Mongoose counter: " + label)
                            .register(registry);
                } else {
                    holder.set(value);
                }
            });
        }
        if (latencyService != null) {
            latencyService.forEachNode((processor, node, snap) -> {
                final String key = processor + "::" + node;
                NodeLatencyHolder holder = latencyValues.get(key);
                if (holder == null) {
                    holder = new NodeLatencyHolder();
                    latencyValues.put(key, holder);
                    final NodeLatencyHolder ref = holder;
                    final Tags tags = Tags.of(
                            Tag.of("processor", processor),
                            Tag.of("node", node));
                    Gauge.builder(latencyPrefix + ".p50", ref, h -> h.p50.doubleValue())
                            .description("Mongoose node latency p50").tags(tags).register(registry);
                    Gauge.builder(latencyPrefix + ".p99", ref, h -> h.p99.doubleValue())
                            .description("Mongoose node latency p99").tags(tags).register(registry);
                    Gauge.builder(latencyPrefix + ".p999", ref, h -> h.p999.doubleValue())
                            .description("Mongoose node latency p999").tags(tags).register(registry);
                    Gauge.builder(latencyPrefix + ".max", ref, h -> h.max.doubleValue())
                            .description("Mongoose node latency max").tags(tags).register(registry);
                    Gauge.builder(latencyPrefix + ".count", ref, h -> h.count.doubleValue())
                            .description("Mongoose node latency sample count").tags(tags).register(registry);
                }
                holder.update(snap);
            });
        }
    }

    private void sampleSafely() {
        try {
            sample();
        } catch (Throwable t) {
            // Never propagate — the scheduler would suppress further runs.
            log.warn("Micrometer bridge sample failed", t);
        }
    }

    /** Counter meter name builder. Mongoose labels are already dot-namespaced,
     *  so passthrough plus a fixed prefix produces a stable identifier.
     *  Backend-specific name munging (e.g. Prometheus's dash → underscore)
     *  is the backend's responsibility, not ours. */
    private String meterNameForCounter(String label) {
        if (label == null || label.isEmpty()) return counterPrefix + ".unknown";
        if (counterPrefix == null || counterPrefix.isEmpty()) return label;
        return counterPrefix + "." + label;
    }

    /** Mutable holder for a latency snapshot — Micrometer Gauges read
     *  values via lambda; we update fields per sampling tick. */
    private static final class NodeLatencyHolder {
        final AtomicLong count = new AtomicLong();
        final AtomicLong p50   = new AtomicLong();
        final AtomicLong p99   = new AtomicLong();
        final AtomicLong p999  = new AtomicLong();
        final AtomicLong max   = new AtomicLong();
        void update(NodeLatencySnapshot s) {
            count.set(s.count());
            p50.set(s.p50());
            p99.set(s.p99());
            p999.set(s.p999());
            max.set(s.max());
        }
    }

    // ── test surface ─────────────────────────────────────────────────────

    /** Visible for tests — drive a sampling pass deterministically. */
    void sampleNow() { sample(); }

    /** Visible for tests — count of distinct counters seen so far. */
    int counterMetersSize() { return counterValues.size(); }

    /** Visible for tests — count of distinct latency rows seen so far. */
    int latencyRowsSize() { return latencyValues.size(); }

    /** Visible for tests — current registry (lets tests provide a
     *  SimpleMeterRegistry and assert on its meters). */
    MeterRegistry registry() { return registry; }

    /** Visible for tests — override registry without going through the
     *  @ServiceRegistered injection. */
    void setRegistryForTest(MeterRegistry r) { this.registry = r; }

    /** Visible for tests — override services without service-loader. */
    void setCountersServiceForTest(MongooseCountersService s) { this.countersService = s; }
    void setLatencyServiceForTest(MongooseLatencyService s) { this.latencyService = s; }
}
