/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.telamin.fluxtion.runtime.audit.EventLogger;
import com.telamin.mongoose.connector.file.FileEventSource;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.plugin.lib.json.StateJson;
import com.telamin.mongoose.plugin.svc.cache.fakedelivery.FakeDeliveryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

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
        final Map<String, String> renames = new HashMap<>();

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
        protected String renamedFrom(String feed) {
            return renames.get(feed);
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

    // ---- a renamed feed: its read pointer, and so its epoch, carries over to the new name ----------------------

    @Test
    void a_renamed_feed_resumes_its_saved_position_by_epoch() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        Counter first = counter(cache, fake());
        for (long pos = 10; pos <= 30; pos += 10) {
            FakeDeliveryContext.enter("tills", pos / 10, 7, pos);
            first.cycle(T0);
        }
        // crash after saving 30, before the pointer committed it; the restart renames the feed "tills" -> "tillFeed"
        Counter restarted = counter(cache, fake());
        restarted.renames.put("tillFeed", "tills");
        FakeDeliveryContext.enter("tillFeed", 1, 7, 30L);
        assertFalse(restarted.cycle(T0), "re-delivered under the new name: already applied");
        assertEquals(List.of("replayedSkip=30"), restarted.audit);
        FakeDeliveryContext.enter("tillFeed", 2, 7, 40L);
        assertTrue(restarted.cycle(T0), "new line");
        assertEquals(4, restarted.count);

        JsonNode saved = StateJson.mapper().readTree((String) cache.get("test.counter"));
        assertEquals(40, saved.at("/positions/tillFeed/position").asLong(), "saved under the new name");
        assertTrue(saved.at("/positions/tills").isMissingNode(), "the old name's entry moved, not copied");

        Counter again = counter(cache, fake());
        FakeDeliveryContext.enter("tillFeed", 1, 7, 40L);
        assertFalse(again.cycle(T0), "the next restart finds it by name");
    }

    @Test
    void undeclared_feeds_never_take_over_saved_positions() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("test.counter", "{\"_v\":3,\"state\":{\"count\":1},\"positions\":{"
                + "\"a\":{\"epoch\":7,\"position\":100},\"z\":{\"epoch\":0,\"position\":100}}}");
        Counter c = counter(cache, fake());
        FakeDeliveryContext.enter("b", 1, 8, 50L);
        assertTrue(c.cycle(T0), "another epoch: another input");
        FakeDeliveryContext.enter("y", 1, 0, 50L);
        assertTrue(c.cycle(T0), "epoch 0: a source without epochs never matches");
        assertTrue(c.appliedPositions().containsKey("a"), "the unmatched entry stays");

        FakeDeliveryContext.enter("a", 1, 7, 100L);
        assertFalse(c.cycle(T0), "the feed itself, by name");
        FakeDeliveryContext.enter("c", 1, 7, 50L);
        assertTrue(c.cycle(T0), "a feed that delivered in this run keeps its entry");
        assertEquals(new PersistentNode.Applied(7, 100), c.appliedPositions().get("a"));

        cache.put("test.counter", "{\"_v\":3,\"state\":{\"count\":1},\"positions\":{"
                + "\"a\":{\"epoch\":7,\"position\":100},\"b\":{\"epoch\":7,\"position\":100}}}");
        Counter shared = counter(cache, fake());
        FakeDeliveryContext.enter("c", 1, 7, 50L);
        assertTrue(shared.cycle(T0), "two entries with the epoch: neither is taken");
        assertEquals(new PersistentNode.Applied(7, 100), shared.appliedPositions().get("a"));
        assertEquals(new PersistentNode.Applied(7, 100), shared.appliedPositions().get("b"));
    }

    // ---- guard on: Mongoose's own DeliveryContext, when this build runs against a Mongoose that has it --------

    @Test
    void a_new_feed_with_a_shared_epoch_is_not_assumed_to_be_a_rename() {
        InMemoryCache cache = new InMemoryCache();
        Counter first = counter(cache, fake());
        FakeDeliveryContext.enter("original", 1, 7, 100L);
        assertTrue(first.cycle(T0));

        Counter restarted = counter(cache, fake());
        FakeDeliveryContext.enter("additional", 1, 7, 100L);
        assertTrue(restarted.cycle(T0), "a new logical feed has not applied this event; epoch equality is not rename consent");
        assertEquals(2, restarted.count);
        assertEquals(new PersistentNode.Applied(7, 100), restarted.appliedPositions().get("original"));
    }

    @Test
    void a_rename_into_a_historical_name_uses_the_matching_epoch() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("test.counter", "{\"_v\":3,\"state\":{\"count\":2},\"positions\":{"
                + "\"old\":{\"epoch\":7,\"position\":100},\"new\":{\"epoch\":8,\"position\":100}}}");
        Counter restarted = counter(cache, fake());
        restarted.renames.put("new", "old");
        FakeDeliveryContext.enter("new", 1, 7, 100L);
        assertFalse(restarted.cycle(T0), "a historical entry with another epoch must not hide the renamed input's saved position");
        assertEquals(2, restarted.count);
    }

    @Test
    void a_declared_rename_with_a_reset_or_zero_epoch_applies_the_new_event() {
        for (long epoch : new long[]{8, 0}) {
            InMemoryCache cache = new InMemoryCache();
            cache.put("test.counter", "{\"_v\":3,\"state\":{\"count\":1},\"positions\":{"
                    + "\"old\":{\"epoch\":7,\"position\":100}}}");
            Counter c = counter(cache, fake());
            c.renames.put("new", "old");
            FakeDeliveryContext.enter("new", 1, epoch, 50L);
            assertTrue(c.cycle(T0), "a reset or epoch-less source must not inherit another position space");
            assertEquals(2, c.count);
            assertEquals(new PersistentNode.Applied(7, 100), c.appliedPositions().get("old"));
        }
    }

    @Test
    void an_explicit_rename_selects_its_predecessor_even_when_another_feed_shares_the_epoch() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("test.counter", "{\"_v\":3,\"state\":{\"count\":2},\"positions\":{"
                + "\"old\":{\"epoch\":7,\"position\":100},\"other\":{\"epoch\":7,\"position\":200}}}");
        Counter c = counter(cache, fake());
        c.renames.put("new", "old");
        FakeDeliveryContext.enter("new", 1, 7, 100L);
        assertFalse(c.cycle(T0), "the declaration selects old, not the furthest shared-epoch position");
        FakeDeliveryContext.enter("new", 2, 7, 150L);
        assertTrue(c.cycle(T0), "other's 200 must not skip this input's unapplied 150");
        assertEquals(3, c.count);
        assertEquals(new PersistentNode.Applied(7, 200), c.appliedPositions().get("other"));
    }

    @Test
    void a_declared_predecessor_that_is_still_delivering_is_refused_before_state_changes() {
        InMemoryCache cache = new InMemoryCache();
        Counter c = counter(cache, fake());
        c.renames.put("new", "old");
        FakeDeliveryContext.enter("old", 1, 7, 100L);
        assertTrue(c.cycle(T0));
        Object saved = cache.get("test.counter");
        FakeDeliveryContext.enter("new", 1, 7, 100L);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> c.cycle(T0));
        assertTrue(refused.getMessage().contains("old feed delivered in this run"));
        assertEquals(1, c.count);
        assertEquals(saved, cache.get("test.counter"));
    }

    @Test
    void a_declared_predecessor_that_delivers_after_the_take_over_is_refused() {
        InMemoryCache cache = new InMemoryCache();
        Counter first = counter(cache, fake());
        FakeDeliveryContext.enter("old", 1, 7, 100L);
        assertTrue(first.cycle(T0));

        Counter c = counter(cache, fake());
        c.renames.put("new", "old");
        FakeDeliveryContext.enter("new", 1, 7, 100L);
        assertFalse(c.cycle(T0), "taken over: already applied");
        Object saved = cache.get("test.counter");
        FakeDeliveryContext.enter("old", 1, 7, 100L);   // the old feed is still configured on the same pointer
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> c.cycle(T0),
                "the old name lost its entry to the rename: re-applying its line would count it twice");
        assertTrue(refused.getMessage().contains("taken over by a declared rename"));
        assertEquals(1, c.count);
        assertEquals(saved, cache.get("test.counter"));
    }

    @Test
    void real_file_pointer_epochs_require_explicit_rename_consent(@TempDir Path dir) throws Exception {
        assumeTrue(realDeliveryContextPresent(), "this Mongoose has no DeliveryContext");
        Path input = dir.resolve("input.txt");
        Files.writeString(input, "DEMO\n");
        FileEventSource firstSource = new FileEventSource();
        FileEventSource secondSource = new FileEventSource();
        firstSource.setFilename(input.toString());
        secondSource.setFilename(input.toString());
        EventFlowManager manager = new EventFlowManager();
        firstSource.setEventFlowManager(manager, "original");
        secondSource.setEventFlowManager(manager, "additional");
        Class<?> ctx = Class.forName(DeliveryAccess.CONTEXT_CLASS);
        Method enter = ctx.getMethod("enter", String.class, long.class, long.class, Object.class);
        Method exit = ctx.getMethod("exit");
        try {
            firstSource.start();
            secondSource.start();
            long epoch = (long) FileEventSource.class.getMethod("epoch").invoke(firstSource);
            assertNotEquals(0, epoch);
            assertEquals(epoch, FileEventSource.class.getMethod("epoch").invoke(secondSource),
                    "distinct file feeds on the same input really share its pointer's epoch");

            InMemoryCache cache = new InMemoryCache();
            Counter original = new Counter();
            original.cacheRegistered(cache);
            enter.invoke(null, "original", 1L, epoch, 5L);
            assertTrue(original.cycle(T0));
            ctx.getMethod("runCompletions").invoke(null);
            InMemoryCache renameCache = new InMemoryCache();
            renameCache.put("test.counter", cache.get("test.counter"));

            Counter additional = new Counter();
            additional.cacheRegistered(cache);
            enter.invoke(null, "additional", 1L, epoch, 5L);
            assertTrue(additional.cycle(T0), "same pointer epoch does not prove that this new feed's event was applied");
            ctx.getMethod("runCompletions").invoke(null);
            assertEquals(2, additional.count);

            Counter renamed = new Counter();
            renamed.renames.put("renamed", "original");
            renamed.cacheRegistered(renameCache);
            enter.invoke(null, "renamed", 1L, epoch, 5L);
            assertFalse(renamed.cycle(T0), "an explicitly declared rename retains its replay guard through the real context");
            ctx.getMethod("runCompletions").invoke(null);
            assertEquals(1, renamed.count);
        } finally {
            exit.invoke(null);
            try {
                secondSource.stop();
            } finally {
                firstSource.stop();
            }
        }
    }

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
