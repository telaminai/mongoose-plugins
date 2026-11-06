/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.telamin.fluxtion.runtime.audit.EventLogger;
import com.telamin.mongoose.plugin.lib.json.StateJson;
import com.telamin.mongoose.plugin.svc.cache.fakedelivery.FakeDeliveryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A second event cycle under one delivery (an event a handler or sink queued with {@code onEvent} while the delivery
 * was being handled) is refused: its source position was saved at the first cycle's {@code @AfterEvent}, so it can
 * be neither skipped as a replay (it is new) nor applied safely (a crash before its save would skip it on restart,
 * and the redelivered outer event, skipped, never queues it again).
 */
class PersistentNodeReentrantTest {

    public record State(int balance) {
    }

    /** An account whose deposit handlers are guarded, as an application's are. */
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
        a.deliveryAccess(DeliveryAccess.resolve(FakeDeliveryContext.class.getName(),
                PersistentNodeReentrantTest.class.getClassLoader()));
        a.cacheRegistered(cache);
        return a;
    }

    private static int savedBalance(Cache cache) throws Exception {
        JsonNode record = StateJson.mapper().readTree((String) cache.get("test.account"));
        return record.at("/state/balance").asInt();
    }

    @AfterEach
    void clear() {
        FakeDeliveryContext.exit();
    }

    @Test
    void an_event_queued_during_a_delivery_is_refused_not_skipped_as_a_replay() {
        InMemoryCache cache = new InMemoryCache();
        Account a = account(cache);
        FakeDeliveryContext.enter("payments", 1, 7, 100L);
        assertTrue(a.cycle(10), "the delivered event");

        // the queued event runs under the same delivery, after the first cycle's save
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> a.cycle(5),
                "a queued event must not be silently skipped as an already-applied replay");
        assertTrue(refused.getMessage().contains("payments"), refused.getMessage());
        assertTrue(refused.getMessage().contains("reentrant"), refused.getMessage());
        assertEquals(10, a.balance, "not applied");
        assertEquals(List.of(), a.audit, "not audited as a replay: it is not one");

        // the next delivery is handled as usual
        FakeDeliveryContext.enter("payments", 2, 7, 200L);
        assertTrue(a.cycle(1));
        assertEquals(11, a.balance);
    }

    @Test
    void a_crash_at_the_first_cycles_save_ends_where_the_refusing_live_run_does() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        Account live = account(cache);
        FakeDeliveryContext.enter("payments", 1, 7, 100L);
        live.cycle(10);
        // the intermediate boundary: the record already holds the delivery's position
        JsonNode record = StateJson.mapper().readTree((String) cache.get("test.account"));
        assertEquals(100, record.at("/positions/payments/position").asLong());
        assertEquals(10, savedBalance(cache));

        // crash here: the restarted node skips the re-delivered event, so the queued one is never queued again
        InMemoryCache afterCrash = new InMemoryCache();
        afterCrash.put("test.account", cache.get("test.account"));
        Account restarted = account(afterCrash);
        FakeDeliveryContext.enter("payments", 1, 7, 100L);
        assertFalse(restarted.cycle(10), "re-delivered: already applied");
        assertEquals(10, restarted.balance);

        // without the crash the queued event is refused, never applied: both runs end at the same state, and the
        // live run reports the refusal instead of applying an effect a crash could lose
        FakeDeliveryContext.enter("payments", 1, 7, 100L);
        assertThrows(IllegalStateException.class, () -> live.cycle(5));
        assertEquals(restarted.balance, live.balance);
        assertEquals(10, savedBalance(cache));
    }

    @Test
    void an_unguarded_second_cycle_is_saved_and_then_refused() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        Account a = account(cache);
        FakeDeliveryContext.enter("payments", 1, 7, 100L);
        a.cycle(10);
        a.balance += 5;   // a handler that does not call replayed(), or a nested processReentrantEvent cycle
        IllegalStateException refused = assertThrows(IllegalStateException.class, a::saveState);
        assertTrue(refused.getMessage().contains("reentrant"), refused.getMessage());
        assertEquals(15, savedBalance(cache), "the saved record still matches the node's state");
    }

    @Test
    void separate_deliveries_at_the_same_position_in_a_new_epoch_or_feed_are_not_refused() {
        InMemoryCache cache = new InMemoryCache();
        Account a = account(cache);
        FakeDeliveryContext.enter("payments", 1, 7, 100L);
        assertTrue(a.cycle(1));
        FakeDeliveryContext.enter("payments", 2, 8, 100L);
        assertTrue(a.cycle(1), "a new epoch");
        FakeDeliveryContext.enter("refunds", 1, 8, 100L);
        assertTrue(a.cycle(1), "another feed");
        FakeDeliveryContext.exit();
        assertTrue(a.cycle(1), "no delivery");
        assertTrue(a.cycle(1), "no delivery, twice: a timer is never refused");
        assertEquals(5, a.balance);
    }
}
