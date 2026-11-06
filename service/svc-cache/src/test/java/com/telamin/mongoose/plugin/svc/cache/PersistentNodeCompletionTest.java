/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.telamin.fluxtion.runtime.audit.EventLogger;
import com.telamin.mongoose.plugin.lib.json.StateJson;
import com.telamin.mongoose.plugin.svc.cache.fakedelivery.FakeHookDeliveryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With a Mongoose that has the delivery-complete hook ({@code DeliveryContext.onComplete}, here a test double of it),
 * a PersistentNode saves its state and the delivery's position once per delivery, when the delivery is complete, after
 * every event the delivery queued reentrantly (review finding F1). A queued event is applied, not skipped or refused;
 * a crash before completion saves nothing, so the re-delivery re-applies the whole delivery; a retry of a cycle the
 * node completed is skipped (its effect is in memory); events without a position are saved at once, as before.
 */
class PersistentNodeCompletionTest {

    public record State(int balance) {
    }

    static class Account extends PersistentNode<State> {
        int balance;
        final List<String> audit = new ArrayList<>();

        Account() {
            auditLog = new EventLogger(null, null) {
                @Override
                public EventLogger info(String key, long value) {
                    audit.add(key + "=" + value);
                    return this;
                }
            };
        }

        boolean deposit(int amount) {
            if (replayed()) {
                return false;
            }
            balance += amount;
            return true;
        }

        /** One event cycle as the generated processor runs it: the handler, then the @AfterEvent save. */
        boolean cycle(int amount) {
            boolean applied = deposit(amount);
            saveState();
            return applied;
        }

        @Override
        protected String stateKey() {
            return "test.account";
        }

        @Override
        protected State state() {
            return new State(balance);
        }

        @Override
        protected void restore(State saved) {
            balance = saved.balance();
        }
    }

    private static Account account(Cache cache) {
        Account a = new Account();
        a.deliveryAccess(DeliveryAccess.resolve(FakeHookDeliveryContext.class.getName(),
                PersistentNodeCompletionTest.class.getClassLoader()));
        a.cacheRegistered(cache);
        return a;
    }

    private static JsonNode saved(Cache cache) throws Exception {
        Object json = cache.get("test.account");
        return json == null ? null : StateJson.mapper().readTree((String) json);
    }

    /** Counts the writes of the node's record. */
    static final class CountingCache extends InMemoryCache {
        int puts;

        @Override
        public void put(String key, Object value) {
            puts++;
            super.put(key, value);
        }
    }

    @AfterEach
    void clear() {
        FakeHookDeliveryContext.exit();
    }

    @Test
    void the_hook_is_detected() {
        assertTrue(DeliveryAccess.resolve(FakeHookDeliveryContext.class.getName(), getClass().getClassLoader()).hook());
        assertFalse(DeliveryAccess.resolve(
                com.telamin.mongoose.plugin.svc.cache.fakedelivery.FakeDeliveryContext.class.getName(),
                getClass().getClassLoader()).hook(), "a DeliveryContext without onComplete: no hook");
    }

    @Test
    void an_event_queued_during_a_delivery_is_applied_and_the_delivery_saved_once_at_completion() throws Exception {
        CountingCache cache = new CountingCache();
        Account a = account(cache);
        FakeHookDeliveryContext.enter("payments", 1, 7, 100L);
        assertTrue(a.cycle(10), "the delivered event");
        assertTrue(a.cycle(5), "the event it queued: new work, applied");
        assertEquals(0, cache.puts, "nothing saved before the delivery is complete");
        assertEquals(1, FakeHookDeliveryContext.pending(), "one callback per delivery");

        FakeHookDeliveryContext.complete();
        assertEquals(1, cache.puts, "one write per delivery");
        JsonNode record = saved(cache);
        assertEquals(15, record.at("/state/balance").asInt(), "the queued event's effect is in the saved state");
        assertEquals(100, record.at("/positions/payments/position").asLong());
        assertEquals(7, record.at("/positions/payments/epoch").asLong());
        assertEquals(List.of(), a.audit, "nothing skipped");
    }

    @Test
    void a_crash_before_completion_saves_nothing_so_the_restart_re_applies_the_whole_delivery() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        Account live = account(cache);
        FakeHookDeliveryContext.enter("payments", 1, 7, 100L);
        live.cycle(1);
        FakeHookDeliveryContext.complete();          // delivery 1 complete: saved
        FakeHookDeliveryContext.enter("payments", 2, 7, 200L);
        live.cycle(10);
        live.cycle(5);                               // queued by the first

        // crash here, before delivery 2's completion: the record holds delivery 1 only
        InMemoryCache afterCrash = new InMemoryCache();
        afterCrash.put("test.account", cache.get("test.account"));
        assertEquals(1, saved(afterCrash).at("/state/balance").asInt());
        assertEquals(100, saved(afterCrash).at("/positions/payments/position").asLong());

        // the live run, which did not crash, completes delivery 2
        FakeHookDeliveryContext.complete();
        assertEquals(16, live.balance);

        // the restart: the feed re-delivers 2 (never acknowledged: its completion did not run), and the whole delivery,
        // the queued event included (its handler queues it again), is applied again
        FakeHookDeliveryContext.exit();
        Account restarted = account(afterCrash);
        FakeHookDeliveryContext.enter("payments", 2, 7, 200L);
        assertTrue(restarted.cycle(10), "not a replay: its effect was never saved");
        assertTrue(restarted.cycle(5));
        FakeHookDeliveryContext.complete();
        assertEquals(live.balance, restarted.balance, "the same state as the run that did not crash");
        assertEquals(16, saved(afterCrash).at("/state/balance").asInt());
        assertEquals(200, saved(afterCrash).at("/positions/payments/position").asLong());

        // and a re-delivery after the completion is a replay
        FakeHookDeliveryContext.enter("payments", 2, 7, 200L);
        assertFalse(restarted.cycle(10));
        assertEquals(List.of("replayedSkip=200"), restarted.audit);
    }

    @Test
    void a_retry_of_a_cycle_the_node_completed_is_skipped_and_saved_at_the_retrys_completion() throws Exception {
        CountingCache cache = new CountingCache();
        Account a = account(cache);
        FakeHookDeliveryContext.enter("payments", 1, 7, 100L);
        assertTrue(a.cycle(10));
        FakeHookDeliveryContext.dispatchFailed();    // something after the node's cycle threw: the server retries
        assertEquals(0, FakeHookDeliveryContext.pending(), "the failed attempt's callback is discarded");
        assertFalse(a.cycle(10), "the retry: its effect is already in the node, not applied twice");
        assertEquals(10, a.balance);
        assertEquals(List.of("retriedSkip=100"), a.audit);
        FakeHookDeliveryContext.complete();
        assertEquals(1, cache.puts);
        assertEquals(10, saved(cache).at("/state/balance").asInt());
        assertEquals(100, saved(cache).at("/positions/payments/position").asLong());
    }

    @Test
    void a_second_cycle_before_the_event_a_synthetic_clock_install_is_not_refused() throws Exception {
        CountingCache cache = new CountingCache();
        Account a = account(cache);
        FakeHookDeliveryContext.enter("payments", 1, 7, 100L);
        a.saveState();                               // the cycle that installs the clock: no handler of the node
        assertTrue(a.cycle(10), "the ReplayRecord's event itself");
        FakeHookDeliveryContext.complete();
        assertEquals(1, cache.puts);
        assertEquals(10, saved(cache).at("/state/balance").asInt());
    }

    @Test
    void an_event_without_a_position_is_saved_at_once_and_never_waits_for_a_completion() throws Exception {
        CountingCache cache = new CountingCache();
        Account a = account(cache);
        assertTrue(a.cycle(3));
        assertEquals(1, cache.puts, "a timer or admin event: saved in its cycle, as before");
        assertTrue(a.cycle(3), "a second cycle (a reentrant event of a position-less event) is never refused");
        assertEquals(2, cache.puts);
        assertEquals(6, saved(cache).at("/state/balance").asInt());
        assertTrue(saved(cache).path("positions").isEmpty());
    }

    @Test
    void separate_deliveries_each_complete_on_their_own() throws Exception {
        CountingCache cache = new CountingCache();
        Account a = account(cache);
        for (long seq = 1; seq <= 3; seq++) {
            FakeHookDeliveryContext.enter("payments", seq, 7, seq * 100);
            assertTrue(a.cycle(1));
            FakeHookDeliveryContext.complete();
        }
        assertEquals(3, cache.puts);
        assertEquals(300, saved(cache).at("/positions/payments/position").asLong());
        FakeHookDeliveryContext.enter("payments", 2, 7, 200L);
        assertFalse(a.cycle(1), "a re-delivery of a completed delivery is a replay");
    }

    @Test
    void a_save_that_fails_at_completion_throws_so_the_reader_retries_it() {
        InMemoryCache failing = new InMemoryCache() {
            int calls;

            @Override
            public void put(String key, Object value) {
                if (calls++ == 0) throw new IllegalStateException("DEMO disk full");
                super.put(key, value);
            }
        };
        Account a = account(failing);
        FakeHookDeliveryContext.enter("payments", 1, 7, 100L);
        a.cycle(10);
        assertThrows(IllegalStateException.class, FakeHookDeliveryContext::complete, "the reader sees the failure");
        // the reader retries the callback itself (it is not registered again)
        assertDoesNotThrow(FakeHookDeliveryContext::complete);
        assertNotNull(failing.get("test.account"));
    }
}
