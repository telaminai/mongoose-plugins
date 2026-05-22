/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.service.counters.MongooseCountersService;
import lombok.extern.log4j.Log4j2;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Samples JVM and dispatcher-counters metrics at a fixed interval and
 * fans the snapshot out to subscribers — typically the
 * {@code /ws/monitor} WebSocket connections.
 *
 * <p>Single daemon thread. Subscribers are invoked from that thread; an
 * exception in one subscriber does not block the others.
 *
 * <p>When wired with a {@link MongooseCountersService}, each tick also
 * walks {@code forEachCounter}, computes per-counter rates against the
 * previous snapshot, and bundles the result into {@link JvmSnapshot}'s
 * {@code throughput} field. With the no-op counters service installed,
 * the throughput block is {@code null} and the front-end falls back to
 * JVM-only behaviour.
 */
@Log4j2
final class MonitoringSampler {

    /**
     * Default interval — applied when no client has expressed a preference
     * yet, and used as the floor for {@link #setIntervalMs(long)} (we never
     * undercut whatever the operator configured in YAML).
     */
    private final long defaultIntervalMs;
    private volatile long intervalMs;
    /** {@code true} while no client is requesting samples — the sampler stays
     *  idle to avoid the allocations from {@link #snapshot()}. */
    private volatile boolean paused = false;
    private final List<Consumer<JvmSnapshot>> subscribers = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService executor;
    private final AtomicInteger threadIdx = new AtomicInteger();
    private ScheduledFuture<?> nextTick;

    /** Counters service for throughput sampling. May be the no-op. */
    private final MongooseCountersService counters;
    /** Optional per-tick callback for queue-depth sampling — gives EFM a
     *  chance to write {@code queue.{path}.depth} gauges before we walk the
     *  counters. {@code null} when not wired. */
    private final Runnable beforeTickHook;
    /** Previous tick's counter values, keyed by label, used to compute rates. */
    private Map<String, Long> previousCounterValues = Collections.emptyMap();
    /** Timestamp of the previous tick — for rate = delta / windowMs. */
    private long previousTickTs = 0L;

    MonitoringSampler(long intervalMs) {
        this(intervalMs, com.telamin.mongoose.internal.NoOpCountersService.INSTANCE, null);
    }

    MonitoringSampler(long intervalMs,
                      MongooseCountersService counters,
                      Runnable beforeTickHook) {
        this.defaultIntervalMs = intervalMs;
        this.intervalMs = intervalMs;
        this.counters = counters;
        this.beforeTickHook = beforeTickHook;
    }

    /**
     * Reconfigure the sampling interval at runtime. Callers should clamp the
     * value at the configured default ({@link #defaultIntervalMs}) so the
     * UI cannot drive the server below the operator's policy floor. Pass any
     * non-positive value to {@link #setPaused(boolean)} instead.
     */
    synchronized void setIntervalMs(long ms) {
        long requested = Math.max(ms, defaultIntervalMs);
        if (requested == intervalMs && !paused) return;
        intervalMs = requested;
        paused = false;
        rescheduleLocked();
    }

    /**
     * Suspend or resume sampling. When paused the executor stays alive but no
     * tick fires — when every connected client picks {@code Off} we drop
     * allocations to zero.
     */
    synchronized void setPaused(boolean p) {
        if (paused == p) return;
        paused = p;
        rescheduleLocked();
    }

    long currentIntervalMs() { return intervalMs; }
    boolean isPaused()       { return paused; }

    void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "svc-admin-web-monitor-" + threadIdx.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        rescheduleLocked();
    }

    void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
        nextTick = null;
    }

    /**
     * Cancel any in-flight scheduled tick and book the next one based on
     * the current paused/interval state. Holds the monitor while it
     * mutates the future reference; the tick itself reschedules from
     * inside its run, so the executor never ends up with two pending
     * ticks at once.
     */
    private void rescheduleLocked() {
        if (executor == null) return;
        if (nextTick != null) {
            nextTick.cancel(false);
            nextTick = null;
        }
        if (paused) return;
        nextTick = executor.schedule(this::tickAndReschedule, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void tickAndReschedule() {
        try {
            tick();
        } finally {
            synchronized (this) {
                if (executor != null && !paused) {
                    nextTick = executor.schedule(this::tickAndReschedule, intervalMs, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    void subscribe(Consumer<JvmSnapshot> c) {
        subscribers.add(c);
    }

    void unsubscribe(Consumer<JvmSnapshot> c) {
        subscribers.remove(c);
    }

    int subscriberCount() {
        return subscribers.size();
    }

    private void tick() {
        if (subscribers.isEmpty() || paused) return;
        if (beforeTickHook != null) {
            try {
                beforeTickHook.run();
            } catch (Exception e) {
                log.warn("monitor pre-tick hook threw", e);
            }
        }
        JvmSnapshot snapshot = tickSnapshot();
        for (Consumer<JvmSnapshot> sub : subscribers) {
            try {
                sub.accept(snapshot);
            } catch (Exception e) {
                log.warn("monitor subscriber threw", e);
            }
        }
    }

    /**
     * Per-tick snapshot — JVM + throughput. Holds previous-counter state for
     * rate computation, so it's instance-bound (not static).
     */
    JvmSnapshot tickSnapshot() {
        JvmSnapshot base = snapshot();
        Throughput throughput = counters.isOperational()
                ? buildThroughput(base.ts())
                : null;
        return new JvmSnapshot(base.ts(), base.jvm(), base.queues(), throughput);
    }

    private Throughput buildThroughput(long nowTs) {
        // Walk all registered counters once into a transient map.
        Map<String, Long> current = new HashMap<>();
        counters.forEachCounter((id, label, value) -> current.put(label, value));

        long windowMs = previousTickTs == 0 ? intervalMs : Math.max(1, nowTs - previousTickTs);

        List<NamedRate> feeds = new ArrayList<>();
        List<GroupRate> groups = new ArrayList<>();
        List<NamedRate> processors = new ArrayList<>();
        List<NodeRate> nodes = new ArrayList<>();
        List<QueueDepth> queues = new ArrayList<>();

        for (Map.Entry<String, Long> e : current.entrySet()) {
            String label = e.getKey();
            long value = e.getValue();
            Long prev = previousCounterValues.get(label);
            long delta = (prev == null) ? 0 : Math.max(0, value - prev);
            double ratePerSec = (delta * 1000.0) / windowMs;

            // Label format:
            //   feed.{name}.published
            //   group.{name}.processed   /  group.{name}.idleCycles
            //   processor.{name}.events
            //   node.{processor}.{node}.invocations
            //   queue.{path}.depth
            if (label.startsWith("feed.") && label.endsWith(".published")) {
                String name = stripPrefixSuffix(label, "feed.", ".published");
                feeds.add(new NamedRate(name, value, ratePerSec));
            } else if (label.startsWith("group.") && label.endsWith(".processed")) {
                String name = stripPrefixSuffix(label, "group.", ".processed");
                Long idleVal = current.get("group." + name + ".idleCycles");
                long idle = (idleVal == null) ? 0L : idleVal;
                groups.add(new GroupRate(name, value, ratePerSec, idle));
            } else if (label.startsWith("processor.") && label.endsWith(".events")) {
                String name = stripPrefixSuffix(label, "processor.", ".events");
                processors.add(new NamedRate(name, value, ratePerSec));
            } else if (label.startsWith("node.") && label.endsWith(".invocations")) {
                // label = "node.{processor}.{node}.invocations" — split off the
                // trailing ".invocations", then split the first segment after
                // "node." as processor; everything between is the node name.
                String inner = stripPrefixSuffix(label, "node.", ".invocations");
                int firstDot = inner.indexOf('.');
                if (firstDot > 0) {
                    String processor = inner.substring(0, firstDot);
                    String node = inner.substring(firstDot + 1);
                    nodes.add(new NodeRate(processor, node, value, ratePerSec));
                }
            } else if (label.startsWith("queue.") && label.endsWith(".depth")) {
                String path = stripPrefixSuffix(label, "queue.", ".depth");
                queues.add(new QueueDepth(path, value));
            }
        }

        previousCounterValues = current;
        previousTickTs = nowTs;
        return new Throughput(feeds, groups, processors, nodes, queues);
    }

    private static String stripPrefixSuffix(String s, String prefix, String suffix) {
        return s.substring(prefix.length(), s.length() - suffix.length());
    }

    /** JVM-only snapshot — REST endpoint + initial WS send. No counter access. */
    static JvmSnapshot snapshot() {
        long ts = System.currentTimeMillis();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        List<GcInfo> gcs = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcs.add(new GcInfo(gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
        }
        JvmInfo jvm = new JvmInfo(
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                threads.getThreadCount(),
                gcs);
        return new JvmSnapshot(ts, jvm, Collections.emptyList(), null);
    }

    /** Server identity snapshot — populated once on demand. */
    static ServerInfo serverInfo() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        return new ServerInfo(
                rt.getName(),
                rt.getVmName() + " " + rt.getVmVersion(),
                rt.getStartTime(),
                rt.getUptime());
    }

    // ---------- DTOs (public for Jackson) ----------

    public record ServerInfo(String pid, String runtime, long startTime, long uptimeMs) { }

    public record JvmSnapshot(long ts, JvmInfo jvm, List<QueueInfo> queues, Throughput throughput) { }

    public record JvmInfo(
            long heapUsed,
            long heapCommitted,
            long heapMax,
            long nonHeapUsed,
            int threads,
            List<GcInfo> gc) { }

    public record GcInfo(String name, long count, long timeMs) { }

    /** Queue introspection lands in a follow-up; v1 always emits []. See spec §10.2. */
    public record QueueInfo(String name, int depth, int capacity) { }

    /**
     * Throughput block emitted on every tick when a real counters service
     * is wired. Rates are events per second over the sampling window
     * ({@code delta / (nowTs - previousTickTs)} × 1000). On the first tick
     * after subscriber connect, the rate is zero — no previous snapshot to
     * diff against.
     */
    public record Throughput(
            List<NamedRate> feeds,
            List<GroupRate> groups,
            List<NamedRate> processors,
            List<NodeRate> nodes,
            List<QueueDepth> queues) { }

    public record NamedRate(String name, long total, double rate) { }
    public record GroupRate(String name, long total, double rate, long idleCycles) { }
    public record NodeRate(String processor, String node, long total, double rate) { }
    public record QueueDepth(String path, long depth) { }
}
