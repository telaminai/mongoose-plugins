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

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PersistentNodeTest {

    public record State(int count, LocalDateTime last) {
    }

    /** A counter that skips replayed events, as an application handler does. */
    static class Counter extends PersistentNode<State> {
        int count;
        LocalDateTime last;
        final List<String> audit = new ArrayList<>();

        Counter() {
            auditLog = new EventLogger(null, null) {
                @Override
                public EventLogger info(String key, long value) {
                    audit.add(key + "=" + value);
                    return this;
                }
            };
        }

        boolean onEvent(LocalDateTime at) {
            if (replayed()) {
                return false;
            }
            count++;
            last = at;
            return true;
        }

        /** One event cycle: handler then the @AfterEvent save. */
        boolean cycle(LocalDateTime at) {
            boolean changed = onEvent(at);
            saveState();
            return changed;
        }

        @Override
        protected String stateKey() {
            return "test.counter";
        }

        @Override
        protected State state() {
            return new State(count, last);
        }

        @Override
        protected void restore(State saved) {
            count = saved.count();
            last = saved.last();
        }
    }

    private static final LocalDateTime T0 = LocalDateTime.parse("2026-03-01T09:30:00");

    private static boolean realDeliveryContextPresent() {
        try {
            Class.forName(DeliveryAccess.CONTEXT_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static DeliveryAccess fake() {
        DeliveryAccess access = DeliveryAccess.resolve(FakeDeliveryContext.class.getName(),
                PersistentNodeTest.class.getClassLoader());
        assertTrue(access.enabled());
        return access;
    }

    private static Counter counter(Cache cache, DeliveryAccess access) {
        Counter c = new Counter();
        c.deliveryAccess(access);
        c.cacheRegistered(cache);
        return c;
    }

    @AfterEach
    void clear() {
        FakeDeliveryContext.exit();
    }

    @Test
    void state_is_saved_after_each_event_and_restored_on_start() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        Counter first = counter(cache, DeliveryAccess.OFF);
        first.cycle(T0);
        first.cycle(T0.plusMinutes(1));

        JsonNode saved = StateJson.mapper().readTree((String) cache.get("test.counter"));
        assertEquals(3, saved.get("_v").asInt());
        assertEquals("2026-03-01T09:31", saved.at("/state/last").asText(), "java.time as ISO string");
        assertTrue(saved.get("positions").isEmpty());

        Counter restarted = counter(cache, DeliveryAccess.OFF);
        assertEquals(2, restarted.count);
        assertEquals(T0.plusMinutes(1), restarted.last);
    }

    @Test
    void older_records_are_restored_without_trusting_positions() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("test.counter", "{\"count\":5,\"last\":\"2026-03-01T09:30:00\"}");   // bare state
        assertEquals(5, counter(cache, fake()).count);

        cache.put("test.counter", "{\"_v\":2,\"state\":{\"count\":6},\"positions\":{\"f\":{\"position\":100}}}");
        Counter v2 = counter(cache, fake());
        assertEquals(6, v2.count);
        assertTrue(v2.appliedPositions().isEmpty(), "a v2 record has no epoch: its positions are ignored");
        FakeDeliveryContext.enter("f", 1, 0, 50L);
        assertTrue(v2.cycle(T0), "not skipped on a v2 position");
    }

    @Test
    void seed_runs_before_restore_and_keeps_stored_values() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("cfg.limit", "9");
        Counter c = new Counter() {
            @Override
            protected void onCache(Cache cache) {
                Seed.value(cache, "cfg.limit", "1");
                Seed.value(cache, "cfg.name", "shop");
            }
        };
        c.deliveryAccess(DeliveryAccess.OFF);
        c.cacheRegistered(cache);
        assertEquals("9", cache.get("cfg.limit"));
        assertEquals("shop", cache.get("cfg.name"));
    }

    // ---- guard off: the Mongoose on this build's class path -------------------------------------------------

    @Test
    void guard_is_off_without_a_delivery_context() {
        assumeFalse(realDeliveryContextPresent(), "this Mongoose has DeliveryContext");
        assertFalse(DeliveryAccess.DEFAULT.enabled());
        assertNull(DeliveryAccess.DEFAULT.current());

        InMemoryCache cache = new InMemoryCache();
        Counter c = new Counter();   // default access, as in an application
        c.cacheRegistered(cache);
        assertTrue(c.cycle(T0));
        assertTrue(c.cycle(T0));
        assertEquals(2, c.count, "nothing is ever skipped");
        assertTrue(c.appliedPositions().isEmpty());
        assertTrue(c.audit.isEmpty());
    }

    @Test
    void a_class_without_the_delivery_shape_turns_the_guard_off() {
        assertFalse(DeliveryAccess.resolve("java.lang.Object", getClass().getClassLoader()).enabled());
        assertFalse(DeliveryAccess.resolve("no.such.DeliveryContext", getClass().getClassLoader()).enabled());
    }

    // ---- guard on: a delivery context with Mongoose's shape --------------------------------------------------

    @Test
    void guard_skips_events_already_in_the_saved_state() {
        InMemoryCache cache = new InMemoryCache();
        Counter first = counter(cache, fake());
        for (long pos = 10; pos <= 30; pos += 10) {
            FakeDeliveryContext.enter("feed", pos / 10, 7, pos);
            assertTrue(first.cycle(T0));
        }
        assertEquals(new PersistentNode.Applied(7, 30), first.appliedPositions().get("feed"));

        // restart: the feed re-delivers 20 and 30 (committed before the crash at 10), then new lines
        Counter restarted = counter(cache, fake());
        assertEquals(3, restarted.count);
        FakeDeliveryContext.enter("feed", 1, 7, 20L);
        assertFalse(restarted.cycle(T0), "already applied");
        FakeDeliveryContext.enter("feed", 2, 7, 30L);
        assertFalse(restarted.cycle(T0), "already applied");
        assertEquals(List.of("replayedSkip=30", "replayedSkip=30"), restarted.audit);
        FakeDeliveryContext.enter("feed", 3, 7, 40L);
        assertTrue(restarted.cycle(T0), "new line");
        assertEquals(4, restarted.count);
        assertEquals(new PersistentNode.Applied(7, 40), restarted.appliedPositions().get("feed"));
    }

    @Test
    void guard_never_skips_a_new_epoch_another_feed_or_an_event_without_a_position() {
        InMemoryCache cache = new InMemoryCache();
        Counter c = counter(cache, fake());
        FakeDeliveryContext.enter("feed", 1, 7, 100L);
        c.cycle(T0);

        FakeDeliveryContext.enter("feed", 1, 8, 50L);
        assertTrue(c.cycle(T0), "new epoch: the source was reset");
        assertEquals(new PersistentNode.Applied(8, 50), c.appliedPositions().get("feed"), "new epoch starts over");
        FakeDeliveryContext.enter("other", 1, 8, 10L);
        assertTrue(c.cycle(T0), "another feed");
        FakeDeliveryContext.enter("feed", 1, 8, "not-a-long");
        assertTrue(c.cycle(T0), "a non-numeric position is not guarded");
        FakeDeliveryContext.exit();
        assertTrue(c.cycle(T0), "no delivery: a timer or an admin command");
        assertEquals(5, c.count);
    }

    // ---- guard on: Mongoose's own DeliveryContext, when this build runs against a Mongoose that has it --------

    @Test
    void guard_uses_mongoose_delivery_context_when_present() throws Exception {
        assumeTrue(realDeliveryContextPresent(), "this Mongoose has no DeliveryContext");
        assertTrue(DeliveryAccess.DEFAULT.enabled());
        Class<?> ctx = Class.forName(DeliveryAccess.CONTEXT_CLASS);
        Method enter = ctx.getMethod("enter", String.class, long.class, long.class, Object.class);
        Method exit = ctx.getMethod("exit");
        try {
            InMemoryCache cache = new InMemoryCache();
            Counter first = new Counter();
            first.cacheRegistered(cache);
            enter.invoke(null, "feed", 1L, 3L, 100L);
            assertTrue(first.cycle(T0));
            if (DeliveryAccess.DEFAULT.hook()) {
                // with the delivery-complete hook the save waits for the reader's completion: run it, as the reader does
                ctx.getMethod("runCompletions").invoke(null);
            }

            Counter restarted = new Counter();
            restarted.cacheRegistered(cache);
            assertFalse(restarted.cycle(T0), "same epoch and position: already applied");
            enter.invoke(null, "feed", 2L, 3L, 101L);
            assertTrue(restarted.cycle(T0));
        } finally {
            exit.invoke(null);
        }
    }
}
