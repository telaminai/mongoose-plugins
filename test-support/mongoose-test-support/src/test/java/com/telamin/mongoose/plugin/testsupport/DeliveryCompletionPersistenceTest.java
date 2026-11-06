/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.RetryPolicy;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.plugin.lib.json.StateJson;
import com.telamin.mongoose.plugin.svc.cache.Cache;
import com.telamin.mongoose.plugin.svc.cache.JsonFileCache;
import com.telamin.mongoose.plugin.testsupport.gym.event.Join;
import com.telamin.mongoose.plugin.testsupport.gym.event.Payment;
import com.telamin.mongoose.plugin.testsupport.gym.generated.GymProcessor;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Review finding F1, fixed with Mongoose's delivery-complete hook ({@code DeliveryContext.onComplete}, 1.1.3): the
 * checked-in GymProcessor (generated), a real JsonFileCache, and Mongoose's own queue reader delivering positioned
 * items. A Join for an existing member alerts, and the alerts sink pays the fee with {@code flow.onEvent(Payment)}:
 * an event queued during the delivery. The PersistentNodes save their state and the position once the delivery,
 * queued payment included, is complete. Skipped on a Mongoose without the hook ({@code -Dmongoose.version=1.1.3-SNAPSHOT}
 * or later runs it).
 */
class DeliveryCompletionPersistenceTest {

    private static final RetryPolicy THREE_ATTEMPTS = new RetryPolicy(3, 0, 0, 1.0, Set.of(RuntimeException.class));

    @TempDir
    Path dir;

    private Class<?> context;
    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<ErrorEvent> reported = new CopyOnWriteArrayList<>();
    private final ErrorListener listener = reported::add;

    @BeforeEach
    void hook() throws Exception {
        try {
            context = Class.forName("com.telamin.mongoose.service.delivery.DeliveryContext");
            context.getMethod("onComplete", Runnable.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            assumeTrue(false, "this Mongoose has no delivery-complete hook");
        }
        ErrorReporting.getReporter().addListener(listener);
    }

    @AfterEach
    void close() throws Exception {
        ErrorReporting.getReporter().removeListener(listener);
        if (context != null) context.getMethod("exit").invoke(null);
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
    }

    private List<ErrorEvent> errors() {
        return reported.stream().filter(e -> e.getSeverity() == ErrorEvent.Severity.ERROR
                || e.getSeverity() == ErrorEvent.Severity.CRITICAL).toList();
    }

    /** A positioned item, as a COMMITED file feed puts it in a subscriber queue. */
    private static Object positioned(long sequence, long position, Object item) throws Exception {
        return Class.forName("com.telamin.mongoose.dispatch.PositionedItem")
                .getConstructor(long.class, Object.class, long.class, Object.class)
                .newInstance(sequence, position, 123L, item);
    }

    private boolean onComplete(Runnable callback) throws Exception {
        return (boolean) context.getMethod("onComplete", Runnable.class).invoke(null, callback);
    }

    /** A started GymProcessor on {@code state}; its alerts sink pays m1's fee when told m1 is already a member. */
    private GymProcessor start(Path state, List<String> alerts, Runnable onAlert) {
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(state.toString());
        cache.init();
        GymProcessor flow = new GymProcessor();
        flow.init();
        flow.registerService(new Service<>(cache, Cache.class, "appState"));
        MessageSink<Object> sink = value -> {
            alerts.add(String.valueOf(value));
            if (onAlert != null) onAlert.run();
            if (String.valueOf(value).contains("already a member")) {
                flow.onEvent(new Payment("m1", 50, "cash"));   // queued: runs after this cycle's @AfterEvent
            }
        };
        flow.registerService(new Service<>(sink, MessageSink.class, "alerts"));
        flow.start();
        flow.startComplete();
        open.add(cache::tearDown);
        open.add(() -> {
            flow.stop();
            flow.tearDown();
        });
        return flow;
    }

    private static EventQueueToEventProcessorAgent reader(OneToOneConcurrentArrayQueue<Object> queue, DataFlow flow) {
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(queue,
                new EventToOnEventInvokeStrategy(), "gym-reader", "input").withRetryPolicy(THREE_ATTEMPTS);
        agent.registerProcessor(flow);
        return agent;
    }

    /** The membership node's saved record, read from a copy of the cache file. */
    private JsonNode membership(Path state) throws Exception {
        Path copy = Files.createTempFile(dir, "read", ".json");
        Files.copy(state, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JsonFileCache reader = new JsonFileCache();
        reader.setFileName(copy.toString());
        reader.init();
        try {
            Object json = reader.get("membership");
            return json == null ? null : StateJson.mapper().readTree((String) json);
        } finally {
            reader.tearDown();
        }
    }

    @Test
    void the_reviewers_case_a_payment_queued_during_a_delivery_runs() throws Exception {
        // IndependentReentrantPersistenceTest of the review: the delivery entered by hand, no reader
        GymProcessor flow = start(dir.resolve("state.json"), new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        assertEquals(50, flow.membership.due("m1"));
        context.getMethod("enter", String.class, long.class, long.class, Object.class).invoke(null, "input", 1L, 123L, 100L);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        assertEquals(0, flow.membership.due("m1"),
                "the payment queued during this delivery must run, not be suppressed as a prior delivery");
    }

    @Test
    void a_queued_payment_is_applied_and_the_delivery_saved_once_after_it_before_it_counts_as_dispatched()
            throws Exception {
        Path state = dir.resolve("state.json");
        Path atCrash = dir.resolve("at-crash.json");
        List<String> alerts = new ArrayList<>();
        // the "crash point": registered by the sink during the cycle, so it runs first at completion, before any
        // PersistentNode's save (they register at @AfterEvent): the state file as a kill there would leave it
        Runnable snapshot = () -> {
            try {
                Files.copy(state, atCrash, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        GymProcessor[] live = new GymProcessor[1];
        live[0] = start(state, alerts, () -> {
            try {
                onComplete(snapshot);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        live[0].onEvent(new Join("m1", "Ada", "MONTHLY"));   // no position: saved in its cycle
        assertEquals(0.0, membership(state).at("/state/members/m1/paid").asDouble());

        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        EventQueueToEventProcessorAgent agent = reader(queue, live[0]);
        queue.offer(positioned(1, 100, new Join("m1", "Ada", "MONTHLY")));
        assertEquals(1, agent.doWork());

        assertEquals(0, live[0].membership.due("m1"), "the queued payment is applied");
        assertTrue(errors().isEmpty(), errors().toString());
        JsonNode saved = membership(state);
        assertEquals(50.0, saved.at("/state/members/m1/paid").asDouble(), "saved after the queued payment");
        assertEquals(100, saved.at("/positions/input/position").asLong(), "with the delivery's position");

        // the crash boundary: before the delivery's completion nothing of it is saved
        JsonNode beforeCompletion = membership(atCrash);
        assertEquals(0.0, beforeCompletion.at("/state/members/m1/paid").asDouble());
        assertTrue(beforeCompletion.at("/positions/input").isMissingNode(), beforeCompletion.toString());
    }

    @Test
    void a_crash_before_completion_restarts_with_the_whole_delivery_re_applied() throws Exception {
        Path state = dir.resolve("state.json");
        Path atCrash = dir.resolve("at-crash.json");
        Runnable snapshot = () -> {
            try {
                Files.copy(state, atCrash, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        List<String> alerts = new ArrayList<>();
        GymProcessor live = start(state, alerts, () -> {
            try {
                onComplete(snapshot);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        live.onEvent(new Join("m1", "Ada", "MONTHLY"));
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, new Join("m1", "Ada", "MONTHLY")));
        reader(queue, live).doWork();
        assertEquals(0, live.membership.due("m1"));

        // kill -9 at the crash point: the restarted server re-delivers line 1 (never acknowledged: it had not counted
        // as dispatched) to a processor restored from the state file as the kill left it
        List<String> restartAlerts = new ArrayList<>();
        GymProcessor restarted = start(atCrash, restartAlerts, null);
        assertEquals(50, restarted.membership.due("m1"), "restored without the interrupted delivery");
        OneToOneConcurrentArrayQueue<Object> again = new OneToOneConcurrentArrayQueue<>(16);
        again.offer(positioned(1, 100, new Join("m1", "Ada", "MONTHLY")));
        reader(again, restarted).doWork();

        assertEquals(1, restartAlerts.size(), "re-applied, not skipped: " + restartAlerts);
        assertEquals(0, restarted.membership.due("m1"), "the queued payment is re-applied with it");
        assertEquals(live.membership.due("m1"), restarted.membership.due("m1"), "the same state as the run that did not crash");
        JsonNode saved = membership(atCrash);
        assertEquals(50.0, saved.at("/state/members/m1/paid").asDouble());
        assertEquals(100, saved.at("/positions/input/position").asLong());

        // a re-delivery after completion is a replay: skipped, no alert, nothing queued
        OneToOneConcurrentArrayQueue<Object> third = new OneToOneConcurrentArrayQueue<>(16);
        third.offer(positioned(1, 100, new Join("m1", "Ada", "MONTHLY")));
        reader(third, restarted).doWork();
        assertEquals(1, restartAlerts.size(), restartAlerts.toString());
        assertTrue(errors().isEmpty(), errors().toString());
    }

    @Test
    void a_replay_record_on_a_positioned_feed_installs_its_synthetic_clock_and_applies_its_event() throws Exception {
        Path state = dir.resolve("state.json");
        GymProcessor flow = start(state, new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        ReplayRecord record = new ReplayRecord();
        record.setEvent(new Payment("m1", 50, "cash"));
        record.setWallClockTime(1_700_000_000_000L);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, record));
        reader(queue, flow).doWork();     // the clock install is an event cycle of its own, under the same delivery

        assertTrue(errors().isEmpty(), "the second cycle is not refused: " + errors());
        assertEquals(0, flow.membership.due("m1"));
        assertEquals(1_700_000_000_000L, flow.clock.getWallClockTime(), "the synthetic clock is installed");
        JsonNode saved = membership(state);
        assertEquals(50.0, saved.at("/state/members/m1/paid").asDouble());
        assertEquals(100, saved.at("/positions/input/position").asLong());
    }

    /** A handwritten processor around the generated one, whose step after the generated cycle fails once. */
    static final class FailsOnceAfter implements DataFlow {
        final GymProcessor flow;
        int fails = 1;

        FailsOnceAfter(GymProcessor flow) {
            this.flow = flow;
        }

        @Override
        public void onEvent(Object event) {
            flow.onEvent(event);
            if (fails-- > 0) throw new IllegalStateException("DEMO a step after the processor's cycle fails once");
        }

        @Override
        public void setClockStrategy(ClockStrategy clockStrategy) {
            flow.setClockStrategy(clockStrategy);
        }
    }

    @Test
    void a_server_retry_of_a_delivery_the_node_completed_is_applied_once_and_not_dropped() throws Exception {
        Path state = dir.resolve("state.json");
        GymProcessor flow = start(state, new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, new Payment("m1", 20, "cash")));
        reader(queue, new FailsOnceAfter(flow)).doWork();

        assertTrue(errors().isEmpty(), "the retry recovers; nothing dropped: " + errors());
        assertEquals(30, flow.membership.due("m1"), "the payment applied once, not again by the retry");
        JsonNode saved = membership(state);
        assertEquals(20.0, saved.at("/state/members/m1/paid").asDouble());
        assertEquals(100, saved.at("/positions/input/position").asLong(), "saved at the retry's completion");
    }

    @Test
    void the_hook_is_on_with_this_mongoose() throws Exception {
        Method attempt = context.getMethod("attempt");
        assertEquals(0, attempt.invoke(null));
        assertFalse(onComplete(() -> fail("no delivery")), "outside a delivery nothing is kept");
    }

    /**
     * Hook review R1 (the reviewer's independent_retry_of_two_generated_cycles_does_not_duplicate_second_payment): a
     * handwritten adapter drives two complete cycles of the generated processor under one positioned input, then a
     * later step fails once. The retry skips both payments; 15 is applied and saved, not 20.
     */
    @Test
    void a_retry_of_two_generated_cycles_does_not_duplicate_the_second_payment() throws Exception {
        Path state = dir.resolve("two-cycles.json");
        GymProcessor flow = start(state, new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow adapter = new DataFlow() {
            int failures = 1;

            @Override
            public void onEvent(Object event) {
                flow.onEvent(new Payment("m1", 10, "cash"));
                flow.onEvent(new Payment("m1", 5, "cash"));
                if (failures-- > 0) throw new IllegalStateException("DEMO after both generated cycles");
            }

            @Override
            public void setClockStrategy(ClockStrategy clockStrategy) {
            }
        };
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, "two payments"));
        reader(queue, adapter).doWork();
        assertTrue(errors().isEmpty(), "retry recovered: " + errors());
        JsonNode record = membership(state);
        assertAll(() -> assertEquals(35, flow.membership.due("m1"), "payments total 15, not 20"),
                () -> assertEquals(15.0, record.at("/state/members/m1/paid").asDouble(), "durable payment total"),
                () -> assertEquals(100, record.at("/positions/input/position").asLong()));
    }

    /** The adapter's step between the two payments fails once: the retry skips the first and applies the second. */
    @Test
    void a_retry_after_a_failure_between_two_generated_cycles_applies_the_second_once() throws Exception {
        Path state = dir.resolve("partial.json");
        GymProcessor flow = start(state, new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow adapter = new DataFlow() {
            int failures = 1;

            @Override
            public void onEvent(Object event) {
                flow.onEvent(new Payment("m1", 10, "cash"));
                if (failures-- > 0) throw new IllegalStateException("DEMO between the generated cycles");
                flow.onEvent(new Payment("m1", 5, "cash"));
            }

            @Override
            public void setClockStrategy(ClockStrategy clockStrategy) {
            }
        };
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, "two payments"));
        reader(queue, adapter).doWork();
        assertTrue(errors().isEmpty(), errors().toString());
        assertEquals(35, flow.membership.due("m1"));
        assertEquals(15.0, membership(state).at("/state/members/m1/paid").asDouble());
    }

    // ---- hook recheck F1 and F2: a retry rolls back; a successful peer keeps its save -------------------------------

    /**
     * The recheck's F1 (independent_retry_must_not_silently_skip_new_followup_after_an_earlier_queued_cycle): the
     * adapter's duplicate Join alerts and the sink queues Payment(50); the adapter fails once before Payment(7). The
     * retry rolls back, the Join alerts again and queues Payment(50) again, and Payment(7) is applied: paid 57.
     */
    @Test
    void a_retry_applies_a_new_payment_after_an_earlier_queued_cycle() throws Exception {
        Path state = dir.resolve("queued-then-new.json");
        List<String> alerts = new ArrayList<>();
        GymProcessor flow = start(state, alerts, null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow adapter = new DataFlow() {
            int failures = 1;

            @Override
            public void onEvent(Object event) {
                flow.onEvent(new Join("m1", "Ada", "MONTHLY"));    // alerts; the sink queues Payment(50)
                if (failures-- > 0) throw new IllegalStateException("DEMO between generated calls");
                flow.onEvent(new Payment("m1", 7, "cash"));        // never reached by the failed attempt
            }

            @Override
            public void setClockStrategy(ClockStrategy strategy) {
            }
        };
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, "join then pay seven"));
        reader(queue, adapter).doWork();
        assertTrue(errors().isEmpty(), errors().toString());
        JsonNode record = membership(state);
        assertEquals(100, record.at("/positions/input/position").asLong());
        assertEquals(57.0, record.at("/state/members/m1/paid").asDouble(), "new payment must not be skipped");
        assertEquals(2, alerts.size(), "the failed attempt's alert is published again by the retry (at-least-once"
                + " outputs): " + alerts);
    }

    /**
     * The recheck's clock variant (independent_first_replay_record_retry_must_apply_business_event_after_clock_cycle):
     * the first attempt's synthetic-clock install is a cycle; the adapter fails before the business event; the retry
     * does not install the clock again. The payment is applied, not taken for the clock cycle.
     */
    @Test
    void a_retry_after_a_clock_install_cycle_applies_the_business_event() throws Exception {
        Path state = dir.resolve("clock-retry.json");
        GymProcessor flow = start(state, new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow adapter = new DataFlow() {
            int failures = 1;

            @Override
            public void onEvent(Object event) {
                if (failures-- > 0) throw new IllegalStateException("DEMO before the business event");
                flow.onEvent(event);
            }

            @Override
            public void setClockStrategy(ClockStrategy strategy) {
                flow.setClockStrategy(strategy);
            }
        };
        ReplayRecord event = new ReplayRecord();
        event.setEvent(new Payment("m1", 20, "cash"));
        event.setWallClockTime(1_700_000_000_000L);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, event));
        reader(queue, adapter).doWork();
        assertTrue(errors().isEmpty(), errors().toString());
        JsonNode record = membership(state);
        assertEquals(100, record.at("/positions/input/position").asLong());
        assertEquals(20.0, record.at("/state/members/m1/paid").asDouble(), "clock installation was not this payment");
    }

    /**
     * The recheck's F2 (independent_clock_callback_ownership_preserves_successful_peer_save_when_second_fails): two
     * processors on one queue, a positioned ReplayRecord; the generated one applies the payment, a handwritten peer fails
     * once and is retried alone. The generated processor is not retried, and its save must still run.
     */
    @Test
    void a_successful_peer_keeps_its_save_when_another_processor_fails() throws Exception {
        Path state = dir.resolve("successful-peer.json");
        GymProcessor first = start(state, new ArrayList<>(), null);
        first.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow second = new DataFlow() {
            int failures = 1;

            @Override
            public void onEvent(Object event) {
                if (failures-- > 0) throw new IllegalStateException("DEMO second failed once");
            }

            @Override
            public void setClockStrategy(ClockStrategy strategy) {
            }
        };
        ReplayRecord event = new ReplayRecord();
        event.setEvent(new Payment("m1", 20, "cash"));
        event.setWallClockTime(1_700_000_000_000L);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        EventQueueToEventProcessorAgent reader = reader(queue, first);
        reader.registerProcessor(second);
        queue.offer(positioned(1, 100, event));
        reader.doWork();
        assertTrue(errors().isEmpty(), errors().toString());
        assertEquals(30, first.membership.due("m1"), "applied once, not retried");
        assertEquals(20.0, membership(state).at("/state/members/m1/paid").asDouble(),
                "successful peer's callback must not be discarded");
        assertEquals(100, membership(state).at("/positions/input/position").asLong());
    }

    /** Several PersistentNodes of one processor each roll back: Membership and FrontDesk apply the retry once. */
    @Test
    void every_persistent_node_of_the_processor_rolls_back_on_a_retry() throws Exception {
        Path state = dir.resolve("several.json");
        List<String> alerts = new ArrayList<>();
        GymProcessor flow = start(state, alerts, null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow adapter = new DataFlow() {
            int failures = 1;

            @Override
            public void onEvent(Object event) {
                flow.onEvent(new Payment("m1", 50, "cash"));
                flow.onEvent(new com.telamin.mongoose.plugin.testsupport.gym.event.CheckIn("m1"));
                if (failures-- > 0) throw new IllegalStateException("DEMO after both nodes changed");
            }

            @Override
            public void setClockStrategy(ClockStrategy strategy) {
            }
        };
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, "pay and check in"));
        reader(queue, adapter).doWork();
        assertTrue(errors().isEmpty(), errors().toString());
        assertEquals(List.of(), alerts, "no 'already checked in' on the retry: FrontDesk rolled back too");
        assertEquals(50.0, membership(state).at("/state/members/m1/paid").asDouble(), "paid once");
        JsonNode frontDesk = record(state, "frontDesk");
        assertEquals("[\"m1\"]", frontDesk.at("/state/inside").toString());
        assertEquals(100, frontDesk.at("/positions/input/position").asLong());
    }

    /** A node's saved record by key, read from a copy of the cache file. */
    private JsonNode record(Path state, String key) throws Exception {
        Path copy = Files.createTempFile(dir, "read", ".json");
        Files.copy(state, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JsonFileCache reader = new JsonFileCache();
        reader.setFileName(copy.toString());
        reader.init();
        try {
            return StateJson.mapper().readTree((String) reader.get(key));
        } finally {
            reader.tearDown();
        }
    }

    // ---- recheck 2 F1: the rollback happens at the failure, before the retry runs any code ------------------------

    /** The reviewer's independent_argument_computation_must_see_rolled_back_state: the adapter pays 40% of what is due. */
    @Test
    void a_retry_computes_its_argument_from_rolled_back_state() throws Exception {
        Path state = dir.resolve("argument-retry.json");
        GymProcessor flow = start(state, new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow adapter = new DataFlow() {
            int failures = 1;

            @Override
            public void onEvent(Object event) {
                flow.onEvent(new Payment("m1", flow.membership.due("m1") * 0.4, "cash"));
                if (failures-- > 0) throw new IllegalStateException("DEMO after the payment");
            }

            @Override
            public void setClockStrategy(ClockStrategy strategy) {
            }
        };
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, "pay forty percent"));
        reader(queue, adapter).doWork();
        assertTrue(errors().isEmpty(), errors().toString());
        assertEquals(100, membership(state).at("/positions/input/position").asLong());
        assertEquals(20.0, membership(state).at("/state/members/m1/paid").asDouble(), "40% of 50, computed once");
    }

    /** The reviewer's independent_read_only_routing_must_see_rolled_back_state: the adapter branches on what is due. */
    @Test
    void a_retry_routes_on_rolled_back_state() throws Exception {
        Path state = dir.resolve("routing-retry.json");
        GymProcessor flow = start(state, new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow adapter = new DataFlow() {
            int failures = 1;

            @Override
            public void onEvent(Object event) {
                if (flow.membership.due("m1") == 50) flow.onEvent(new Payment("m1", 20, "cash"));
                else flow.setClockStrategy(() -> 123L);    // a handlerless generated cycle
                if (failures-- > 0) throw new IllegalStateException("DEMO after the decision and the cycle");
            }

            @Override
            public void setClockStrategy(ClockStrategy strategy) {
            }
        };
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, "conditional payment"));
        reader(queue, adapter).doWork();
        assertTrue(errors().isEmpty(), errors().toString());
        assertEquals(100, membership(state).at("/positions/input/position").asLong());
        assertEquals(20.0, membership(state).at("/state/members/m1/paid").asDouble(), "routing must use baseline state");
    }

    /** A rollback that cannot be done fails closed: nothing is saved, the reader ends, the loss is reported. */
    @Test
    void a_rollback_that_fails_is_never_committed_as_a_success() throws Exception {
        Path state = dir.resolve("rollback-fails.json");
        GymProcessor flow = start(state, new ArrayList<>(), null);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));
        DataFlow adapter = new DataFlow() {
            @Override
            public void onEvent(Object event) {
                flow.onEvent(new Payment("m1", 20, "cash"));
                // a corrupt baseline: the node's restore will throw
                try {
                    java.lang.reflect.Field f = com.telamin.mongoose.plugin.svc.cache.PersistentNode.class
                            .getDeclaredField("baseline");
                    f.setAccessible(true);
                    f.set(flow.membership, "{not json");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("DEMO after the payment");
            }

            @Override
            public void setClockStrategy(ClockStrategy strategy) {
            }
        };
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        queue.offer(positioned(1, 100, "pay"));
        EventQueueToEventProcessorAgent reader = reader(queue, adapter);
        assertThrows(RuntimeException.class, reader::doWork, "the reader fails closed");
        assertTrue(membership(state).at("/positions/input").isMissingNode(), "nothing of the delivery saved");
        assertEquals(0.0, membership(state).at("/state/members/m1/paid").asDouble());
        assertFalse(reported.stream().noneMatch(e -> e.getSeverity() == ErrorEvent.Severity.CRITICAL), reported.toString());
    }
}
