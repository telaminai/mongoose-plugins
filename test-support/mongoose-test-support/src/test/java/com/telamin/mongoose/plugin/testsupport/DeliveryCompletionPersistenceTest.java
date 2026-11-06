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
    void a_server_retry_of_a_delivery_the_node_completed_is_skipped_not_applied_twice_and_not_dropped() throws Exception {
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
}
