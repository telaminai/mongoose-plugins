/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.plugin.svc.cache.Cache;
import com.telamin.mongoose.plugin.svc.cache.JsonFileCache;
import com.telamin.mongoose.plugin.testsupport.gym.event.Join;
import com.telamin.mongoose.plugin.testsupport.gym.event.Payment;
import com.telamin.mongoose.plugin.testsupport.gym.generated.GymProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An event queued reentrantly during a positioned delivery, through the generated processor and Mongoose's own
 * DeliveryContext: the generated code runs {@code @AfterEvent} (the PersistentNodes' saves) before it drains the queued
 * event, so the queued event runs under a delivery whose position is already saved. It is refused, not skipped as a
 * replay. Needs a Mongoose with DeliveryContext ({@code -Dmongoose.version=1.1.3-SNAPSHOT} or later); skipped otherwise.
 */
class ReentrantDeliveryPersistenceTest {

    @TempDir
    Path dir;

    private Method enter;
    private Method exit;
    private final List<AutoCloseable> open = new ArrayList<>();

    @BeforeEach
    void deliveryContext() throws Exception {
        Class<?> ctx;
        try {
            ctx = Class.forName("com.telamin.mongoose.service.delivery.DeliveryContext");
        } catch (ClassNotFoundException e) {
            assumeTrue(false, "this Mongoose has no DeliveryContext");
            return;
        }
        enter = ctx.getMethod("enter", String.class, long.class, long.class, Object.class);
        exit = ctx.getMethod("exit");
    }

    @AfterEach
    void close() throws Exception {
        if (exit != null) {
            exit.invoke(null);
        }
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
    }

    /** A started GymProcessor on {@code state}; its alerts sink pays m1's fee when told m1 is already a member. */
    private GymProcessor start(Path state, List<String> alerts) {
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(state.toString());
        cache.init();
        GymProcessor flow = new GymProcessor();
        flow.init();
        flow.registerService(new Service<>(cache, Cache.class, "appState"));
        MessageSink<Object> sink = value -> {
            alerts.add(String.valueOf(value));
            if (String.valueOf(value).contains("already a member")) {
                flow.onEvent(new Payment("m1", 50, "cash"));   // queued: runs after this cycle's @AfterEvent
            }
        };
        flow.registerService(new Service<>(sink, MessageSink.class, "alerts"));
        flow.start();
        flow.startComplete();
        open.add(cache::tearDown);
        open.add(() -> {
            exit.invoke(null);
            flow.stop();
            flow.tearDown();
        });
        return flow;
    }

    @Test
    void an_event_queued_during_a_delivery_is_refused_not_skipped() throws Exception {
        List<String> alerts = new ArrayList<>();
        GymProcessor flow = start(dir.resolve("state.json"), alerts);
        flow.onEvent(new Join("m1", "Ada", "MONTHLY"));   // no delivery: a fixture-style event
        assertEquals(50, flow.membership.due("m1"));

        enter.invoke(null, "input", 1L, 123L, 100L);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> flow.onEvent(new Join("m1", "Ada", "MONTHLY")));
        assertTrue(refused.getMessage().contains("reentrant"), refused.getMessage());
        assertEquals(50, flow.membership.due("m1"), "the queued payment is refused, not applied and not skipped");
        assertEquals(1, alerts.size(), alerts.toString());

        enter.invoke(null, "input", 2L, 123L, 200L);
        flow.onEvent(new Payment("m1", 50, "cash"));   // the next delivery is handled as usual
        assertEquals(0, flow.membership.due("m1"));
    }

    @Test
    void a_restart_from_the_first_cycles_save_agrees_with_the_refusing_live_run() throws Exception {
        Path state = dir.resolve("state.json");
        List<String> alerts = new ArrayList<>();
        GymProcessor live = start(state, alerts);
        live.onEvent(new Join("m1", "Ada", "MONTHLY"));
        enter.invoke(null, "input", 1L, 123L, 100L);
        assertThrows(IllegalStateException.class, () -> live.onEvent(new Join("m1", "Ada", "MONTHLY")));
        exit.invoke(null);

        // a restart on the file saved at the first cycle's boundary: the feed re-delivers the line, which is skipped,
        // so nothing queues the payment again; the live run refused it, so both end with the fee still due
        Path copy = dir.resolve("after-crash.json");
        java.nio.file.Files.copy(state, copy);
        List<String> restartAlerts = new ArrayList<>();
        GymProcessor restarted = start(copy, restartAlerts);
        enter.invoke(null, "input", 1L, 123L, 100L);
        restarted.onEvent(new Join("m1", "Ada", "MONTHLY"));
        assertEquals(List.of(), restartAlerts, "re-delivered: skipped");
        assertEquals(live.membership.due("m1"), restarted.membership.due("m1"));
        assertEquals(50, restarted.membership.due("m1"));
    }
}
