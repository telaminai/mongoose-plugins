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

    private final long intervalMs;
    private final List<Consumer<JvmSnapshot>> subscribers = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService executor;
    private final AtomicInteger threadIdx = new AtomicInteger();

    MonitoringSampler(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "svc-admin-web-monitor-" + threadIdx.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
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
        if (subscribers.isEmpty()) return;
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
