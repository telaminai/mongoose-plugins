/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import lombok.extern.log4j.Log4j2;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Samples JVM (and, in later iterations, dispatcher) metrics at a fixed
 * interval and fans the snapshot out to subscribers — typically the
 * {@code /ws/monitor} WebSocket connections.
 *
 * <p>Single daemon thread. Subscribers are invoked from that thread; an
 * exception in one subscriber does not block the others.
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

    MonitoringSampler(long intervalMs) {
        this.defaultIntervalMs = intervalMs;
        this.intervalMs = intervalMs;
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
        JvmSnapshot snapshot = snapshot();
        for (Consumer<JvmSnapshot> sub : subscribers) {
            try {
                sub.accept(snapshot);
            } catch (Exception e) {
                log.warn("monitor subscriber threw", e);
            }
        }
    }

    /** One-shot snapshot for the REST endpoint or for the next sampled push. */
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
        return new JvmSnapshot(ts, jvm, Collections.emptyList());
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

    public record JvmSnapshot(long ts, JvmInfo jvm, List<QueueInfo> queues) { }

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
}
