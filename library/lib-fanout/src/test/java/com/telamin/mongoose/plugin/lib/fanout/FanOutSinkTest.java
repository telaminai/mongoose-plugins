/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.lib.fanout;

import com.telamin.fluxtion.runtime.output.MessageSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FanOutSink}. Each test wires the sink with
 *  recording targets directly (no MongooseServer) — the actual
 *  @ServiceRegistered injection is exercised by the integration
 *  example end-to-end. */
class FanOutSinkTest {

    @Test
    void fan_out_delivers_to_every_target() {
        RecordingSink a = new RecordingSink("a");
        RecordingSink b = new RecordingSink("b");
        RecordingSink c = new RecordingSink("c");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("a", "b", "c"));
        fan.onTargetSink(a, "a");
        fan.onTargetSink(b, "b");
        fan.onTargetSink(c, "c");

        fan.accept("hello");
        fan.accept("world");

        assertEquals(List.of("hello", "world"), a.received);
        assertEquals(List.of("hello", "world"), b.received);
        assertEquals(List.of("hello", "world"), c.received);
    }

    @Test
    void unbound_targets_skipped_silently() {
        // Configure three targets but only register two — third is
        // unbound (e.g. plugin hasn't booted yet). Accept calls must
        // not throw; the bound ones still receive.
        RecordingSink a = new RecordingSink("a");
        RecordingSink c = new RecordingSink("c");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("a", "b", "c"));
        fan.onTargetSink(a, "a");
        fan.onTargetSink(c, "c");

        fan.accept("x");

        assertEquals(List.of("x"), a.received);
        assertEquals(List.of("x"), c.received);
    }

    @Test
    void continue_policy_isolates_throwing_target_from_others() {
        RecordingSink good1 = new RecordingSink("good1");
        ThrowingSink bad = new ThrowingSink("bad");
        RecordingSink good2 = new RecordingSink("good2");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("good1", "bad", "good2"));
        fan.setFailurePolicy(FanOutSink.FailurePolicy.CONTINUE);
        fan.setCircuitOpenThreshold(0); // disable circuit breaker for clarity
        fan.onTargetSink(good1, "good1");
        fan.onTargetSink(bad, "bad");
        fan.onTargetSink(good2, "good2");

        // accept must not throw — CONTINUE swallows + logs
        fan.accept("payload");

        assertEquals(List.of("payload"), good1.received);
        assertEquals(List.of("payload"), good2.received);
        assertEquals(1, bad.attempts.get(), "bad target was attempted once");
    }

    @Test
    void fail_fast_propagates_first_exception() {
        RecordingSink a = new RecordingSink("a");
        ThrowingSink bad = new ThrowingSink("bad");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("a", "bad"));
        fan.setFailurePolicy(FanOutSink.FailurePolicy.FAIL_FAST);
        fan.onTargetSink(a, "a");
        fan.onTargetSink(bad, "bad");

        assertThrows(RuntimeException.class, () -> fan.accept("x"));
        assertEquals(List.of("x"), a.received, "a was attempted before bad threw");
    }

    @Test
    void circuit_opens_after_threshold_and_skips_target() {
        RecordingSink good = new RecordingSink("good");
        ThrowingSink bad = new ThrowingSink("bad");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("good", "bad"));
        fan.setFailurePolicy(FanOutSink.FailurePolicy.CONTINUE);
        fan.setCircuitOpenThreshold(3);
        fan.setCircuitOpenMillis(60_000); // long window for the test
        fan.onTargetSink(good, "good");
        fan.onTargetSink(bad, "bad");

        // First 3 calls all attempt bad (and fail). 4th onwards, bad's
        // circuit is open — bad.attempts must NOT increment.
        for (int i = 0; i < 6; i++) {
            fan.accept("msg-" + i);
        }

        assertEquals(6, good.received.size(), "good target received all 6");
        assertEquals(3, bad.attempts.get(), "bad target attempted only 3 times; circuit blocked the rest");
        assertTrue(fan.targetHealthSnapshot().get("bad").circuitOpen(),
                "bad target circuit reports open");
    }

    @Test
    void retry_then_drop_attempts_n_times_then_continues() {
        ThrowingSink bad = new ThrowingSink("bad");
        RecordingSink ok = new RecordingSink("ok");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("bad", "ok"));
        fan.setFailurePolicy(FanOutSink.FailurePolicy.RETRY_THEN_DROP);
        fan.setRetryAttempts(4);
        fan.setCircuitOpenThreshold(0); // disable circuit so each call retries fresh
        fan.onTargetSink(bad, "bad");
        fan.onTargetSink(ok, "ok");

        fan.accept("x");

        // 1 initial + 4 retries = 5 attempts on bad
        assertEquals(5, bad.attempts.get());
        assertEquals(List.of("x"), ok.received, "ok still received after bad dropped");
    }

    @Test
    void health_snapshot_reports_bound_state_and_counters() {
        RecordingSink a = new RecordingSink("a");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("a", "b"));  // b never bound
        fan.onTargetSink(a, "a");

        fan.accept("x");
        fan.accept("y");

        var snap = fan.targetHealthSnapshot();
        assertEquals(2, snap.size(), "health snapshot keeps the configured order");
        var aHealth = snap.get("a");
        assertTrue(aHealth.bound());
        assertEquals(2, aHealth.totalDelivered());
        assertEquals(0, aHealth.consecutiveFailures());

        var bHealth = snap.get("b");
        assertFalse(bHealth.bound(), "b reported as not-bound");
        assertEquals(0, bHealth.totalDelivered());
    }

    @Test
    void late_arriving_target_is_bound_and_starts_receiving() {
        RecordingSink a = new RecordingSink("a");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("a", "b"));
        fan.onTargetSink(a, "a");

        // First accept — b not bound, only a receives
        fan.accept("first");
        assertEquals(List.of("first"), a.received);

        // Now b arrives (via runtime broadcast in real life)
        RecordingSink b = new RecordingSink("b");
        fan.onTargetSink(b, "b");

        fan.accept("second");

        // a received both, b received the second only
        assertEquals(List.of("first", "second"), a.received);
        assertEquals(List.of("second"), b.received);
    }

    @Test
    void unconfigured_target_name_is_ignored_at_registration() {
        // ServiceInjector pumps every matching @ServiceRegistered call;
        // FanOutSink must filter by targetSinkNames so unrelated sinks
        // don't accidentally get caught in the fanout.
        RecordingSink configured = new RecordingSink("configured");
        RecordingSink stranger   = new RecordingSink("stranger");

        FanOutSink fan = new FanOutSink();
        fan.setTargetSinkNames(List.of("configured"));
        fan.onTargetSink(configured, "configured");
        fan.onTargetSink(stranger,   "stranger");

        fan.accept("hi");

        assertEquals(List.of("hi"), configured.received);
        assertEquals(0, stranger.received.size(),
                "stranger sink not in targetSinkNames must not receive");
    }

    // ── Test sinks ──────────────────────────────────────────────────

    /** Minimal MessageSink that records every accepted value. */
    static final class RecordingSink implements MessageSink<Object> {
        final String name;
        final List<Object> received = new ArrayList<>();
        RecordingSink(String name) { this.name = name; }
        @Override public void accept(Object value) { received.add(value); }
        @Override public void setValueMapper(Function<Object, ?> mapper) { /* no-op */ }
    }

    /** MessageSink that always throws — used to exercise failure /
     *  retry / circuit-breaker paths. */
    static final class ThrowingSink implements MessageSink<Object> {
        final String name;
        final AtomicInteger attempts = new AtomicInteger();
        ThrowingSink(String name) { this.name = name; }
        @Override public void accept(Object value) {
            attempts.incrementAndGet();
            throw new RuntimeException("sink '" + name + "' fails by design");
        }
        @Override public void setValueMapper(Function<Object, ?> mapper) { /* no-op */ }
    }
}
