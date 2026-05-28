/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.lib.fanout;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.output.AbstractMessageSink;
import com.telamin.fluxtion.runtime.output.MessageSink;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Policy layer over N downstream {@link MessageSink} targets.
 *
 * <p>One {@code accept(value)} fans out to every configured target.
 * Per-target failure policy + circuit-breaker semantics live here so
 * the underlying sink implementations (file, kafka, multicast, …)
 * stay focused on their transport. This is a config-time wiring
 * decision: a processor writes once to the {@code FanOutSink}, and
 * the operator chooses at deployment time which downstreams that
 * single write fans out to.
 *
 * <p>Target discovery uses Mongoose's standard
 * {@code @ServiceRegistered MessageSink} injection — list the target
 * sink names in {@link #setTargetSinkNames(List)}, and the matching
 * sinks registered with the server (in any order, including via
 * 1.0.18's runtime add-broadcast) will bind automatically.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Compliance: every business write also lands in an audit sink
 *       — operator-controlled, not application-controlled.</li>
 *   <li>Cross-tier deployment: send to a fast in-memory sink AND a
 *       durable file sink for replay-on-restart.</li>
 *   <li>Migration: send to old + new sinks in parallel, compare,
 *       cut over when confidence is high.</li>
 * </ul>
 */
@Experimental
@Log4j2
public class FanOutSink extends AbstractMessageSink<Object> {

    /** Failure policy applied per target when {@code accept} throws.
     *  Default: CONTINUE — fan-out should not let one bad sink take
     *  down the rest. */
    public enum FailurePolicy { CONTINUE, FAIL_FAST, RETRY_THEN_DROP }

    /** Names of the target sinks this fan-out forwards to. Targets
     *  are discovered + bound via {@code @ServiceRegistered} after
     *  the server registers their {@code Service<MessageSink>} entries
     *  — order doesn't matter. */
    @Getter @Setter private List<String> targetSinkNames = new ArrayList<>();

    @Getter @Setter private FailurePolicy failurePolicy = FailurePolicy.CONTINUE;

    /** Consecutive failures before a target's circuit opens. 0 = no
     *  circuit-breaker (target always tried). */
    @Getter @Setter private int circuitOpenThreshold = 5;

    /** Once open, the circuit stays open for this many millis before
     *  half-opening for a probe. */
    @Getter @Setter private long circuitOpenMillis = 30_000;

    /** Retry attempts when {@link FailurePolicy#RETRY_THEN_DROP} is
     *  in force. CONTINUE / FAIL_FAST ignore this. */
    @Getter @Setter private int retryAttempts = 3;

    /** Per-target circuit + counters. Indexed by target name. */
    private final Map<String, TargetState> targets = new ConcurrentHashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    @ServiceRegistered
    public void onTargetSink(MessageSink sink, String name) {
        if (targetSinkNames == null || !targetSinkNames.contains(name)) return;
        TargetState st = targets.computeIfAbsent(name, TargetState::new);
        st.sink = sink;
        log.info("FanOutSink bound target '{}'", name);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void sendToSink(Object value) {
        if (targetSinkNames == null || targetSinkNames.isEmpty()) return;
        long now = System.nanoTime();
        for (String name : targetSinkNames) {
            TargetState st = targets.get(name);
            if (st == null || st.sink == null) continue;  // not yet bound

            if (isCircuitOpen(st, now)) {
                continue; // skip silently — circuit broken
            }

            try {
                deliver(st, value);
                st.recordSuccess();
            } catch (RuntimeException ex) {
                st.recordFailure(now);
                if (circuitOpenThreshold > 0 && st.consecutiveFailures == circuitOpenThreshold) {
                    log.warn("FanOutSink circuit OPEN for target '{}' after {} failures: {}",
                            name, st.consecutiveFailures, ex.toString());
                }
                switch (failurePolicy) {
                    case FAIL_FAST -> throw ex;
                    case CONTINUE -> {
                        log.warn("FanOutSink target '{}' failed (continue): {}", name, ex.toString());
                        // skip + continue to next target
                    }
                    case RETRY_THEN_DROP -> retryThenDrop(st, value, name, ex);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void deliver(TargetState st, Object value) {
        ((MessageSink) st.sink).accept(value);
    }

    /** Circuit-broken if N consecutive failures have happened AND the
     *  re-open window hasn't elapsed yet. The half-open probe is
     *  implicit: once {@code circuitOpenMillis} has passed since the
     *  last failure, this returns false, the next attempt runs, and
     *  either resets (success) or refreshes (failure). */
    private boolean isCircuitOpen(TargetState st, long now) {
        if (circuitOpenThreshold <= 0) return false;
        if (st.consecutiveFailures < circuitOpenThreshold) return false;
        long openWindowNanos = circuitOpenMillis * 1_000_000L;
        return (now - st.lastFailureNanos) < openWindowNanos;
    }

    private void retryThenDrop(TargetState st, Object value, String name, RuntimeException first) {
        for (int i = 0; i < retryAttempts; i++) {
            try {
                deliver(st, value);
                st.recordSuccess();
                return;
            } catch (RuntimeException ex) {
                st.recordFailure(System.nanoTime());
            }
        }
        log.warn("FanOutSink target '{}' dropped after {} retries: {}",
                name, retryAttempts, first.toString());
    }

    /** Read-only snapshot of per-target health — useful for admin /
     *  introspection surfaces. Returned map is ordered by the
     *  configured {@link #getTargetSinkNames()} order. */
    public Map<String, TargetHealth> targetHealthSnapshot() {
        Map<String, TargetHealth> out = new LinkedHashMap<>();
        if (targetSinkNames == null) return out;
        long now = System.nanoTime();
        for (String name : targetSinkNames) {
            TargetState st = targets.get(name);
            if (st == null) {
                out.put(name, new TargetHealth(name, false, 0, 0, false));
            } else {
                out.put(name, new TargetHealth(
                        name,
                        st.sink != null,
                        st.consecutiveFailures,
                        st.totalDelivered,
                        isCircuitOpen(st, now)));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Read-only health record per target. */
    public record TargetHealth(
            String name,
            boolean bound,
            long consecutiveFailures,
            long totalDelivered,
            boolean circuitOpen
    ) {}

    /** Per-target bookkeeping. Mutated only via accept() callbacks +
     *  the @ServiceRegistered binder; not thread-shared. */
    private static final class TargetState {
        final String name;
        volatile MessageSink<?> sink;
        long consecutiveFailures;
        long totalDelivered;
        long lastFailureNanos;

        TargetState(String name) { this.name = name; }

        void recordSuccess() {
            consecutiveFailures = 0;
            totalDelivered++;
        }

        void recordFailure(long now) {
            consecutiveFailures++;
            lastFailureNanos = now;
        }

    }
}
