/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb.it;

import com.telamin.mongoose.ProcessorHandle;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.control.ControlReply;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.telamin.mongoose.plugin.svc.adminweb.it.ScenarioServer.awaitTrue;

/**
 * The failure scenarios a-h, provoked deterministically on a {@link ScenarioServer}. Shared by the REST and
 * UI packs so both observe the same facts. Each method returns once the fact is established in Mongoose
 * (asserted through the Java API, not through the surface under test).
 */
final class Scenarios {

    private Scenarios() { }

    record Quarantine(Fixtures.ThrowingGenerated pricer, Fixtures.Healthy peer, Fixtures.ThrowingHandwritten handwritten) { }

    /** a. Generated processor quarantined; handwritten processor throws (ordinary retry, nothing contained). */
    static Quarantine quarantine(ScenarioServer s) {
        return quarantine(s, false);
    }

    /** As {@link #quarantine(ScenarioServer)}; {@code capture} records pricing/pricer before it fails. */
    static Quarantine quarantine(ScenarioServer s, boolean capture) {
        var pricer = new Fixtures.ThrowingGenerated();
        var peer = new Fixtures.Healthy();
        var hw = new Fixtures.ThrowingHandwritten();
        s.add("pricing", "pricer", () -> pricer);
        s.add("pricing", "peer", () -> peer);
        s.add("hand", "handwritten", () -> hw);
        awaitTrue("both groups subscribed", () -> s.feed.targets() >= 2, 5_000);
        if (capture) {
            String started = s.command("audit.start", "pricing/pricer").body();
            if (!started.contains("RECORDING pricing/pricer")) throw new AssertionError("capture not started: " + started);
        }
        s.feed.publish("tick1");
        s.feed.publish(Fixtures.BOOM);
        s.feed.publish("tick2");
        awaitTrue("pricer quarantined", () -> s.server.controlGroupStatus("pricing").quarantinedProcessors().containsKey("pricer"), 5_000);
        awaitTrue("healthy peer saw all three", () -> peer.events.get() == 3, 5_000);
        awaitTrue("handwritten saw tick2", () -> hw.events.get() >= 3, 5_000);
        return new Quarantine(pricer, peer, hw);
    }

    /**
     * b. A dead group: a VirtualMachineError on the group's thread. The default error handler calls
     * System.exit(-1) (GlobalErrorHandler), so the harness installs a recording handler first; with the
     * default, the operator has no UI at all - the process is gone.
     */
    static List<Throwable> deadGroup(ScenarioServer s) {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        s.server.setDefaultErrorHandler(errors::add);
        s.add("doomed", "ok", Fixtures.Healthy::new);
        try {
            s.server.addEventProcessor("fatal", "doomed", null, () -> { throw new StackOverflowError("simulated fatal"); });
        } catch (RuntimeException expected) {
            // REPORT policy: the add is reported, not thrown; either way the group dies
        }
        awaitTrue("doomed DEAD", () -> s.server.controlGroupStatus("doomed").state() == ControlDriver.GroupState.DEAD, 5_000);
        return errors;
    }

    /** c. A processor whose stop() blocks; returns once a first shutdown came back incomplete. */
    static Fixtures.BlockingStop blockingStop(ScenarioServer s) {
        var blocker = new Fixtures.BlockingStop();
        s.add("slowstop", "blocker", () -> blocker);
        var first = s.server.shutdown();
        if (first.complete()) throw new AssertionError("expected an incomplete stop: " + first);
        return blocker;
    }

    /** d. A capture whose physical close fails, then the resource limit (1) refusing the next start. */
    static void captureCloseFailure(ScenarioServer s) {
        s.add("cap", "rec", Fixtures.Healthy::new);
        s.add("cap", "rec2", Fixtures.Healthy::new);
        awaitTrue("cap running", () -> s.server.controlGroupStatus("cap").state() == ControlDriver.GroupState.RUNNING, 5_000);
        s.failCaptureCloses("cap");
        s.group("cap").setCaptureResourceLimit(1);
    }

    /** e. Overload: park the consumer, publish past the 1024 queue; returns the publisher's drop count. */
    static long overload(ScenarioServer s) throws InterruptedException {
        var parker = new Fixtures.Parker();
        s.add("slow", "consumer", () -> parker);
        awaitTrue("slow subscribed", () -> s.feed.targets() >= 1, 5_000);
        s.feed.publish(Fixtures.BLOCK);
        if (!parker.parked.await(5, TimeUnit.SECONDS)) throw new AssertionError("consumer never parked");
        for (int i = 0; i < 1_100; i++) s.feed.publish("t" + i);
        long dropped = s.feed.publisher().droppedCount();
        if (dropped == 0) throw new AssertionError("no drops provoked");
        parker.release.countDown();
        return dropped;
    }

    record Busy(Fixtures.Parker parker, ProcessorHandle handle) { }

    /** f. Park the group thread and fill its 4096-slot control mailbox with stop requests for one handle. */
    static Busy fullMailbox(ScenarioServer s) throws InterruptedException {
        var parker = new Fixtures.Parker();
        s.add("busy", "consumer", () -> parker);
        awaitTrue("busy subscribed", () -> s.feed.targets() >= 1, 5_000);
        ProcessorHandle handle = s.server.processorHandle("busy", "consumer");
        s.feed.publish(Fixtures.BLOCK);
        if (!parker.parked.await(5, TimeUnit.SECONDS)) throw new AssertionError("group never parked");
        s.group("busy").setControlQueueFullTimeout(Duration.ofMillis(100));
        // Replies to the handle path complete on the server's reply executor, not on this thread, so a refusal
        // is not visible through getNow at once. Fire the first 4096 (the mailbox capacity) blind, then wait
        // briefly on each further reply for the full-mailbox refusal.
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 4_200; i++) {
            var reply = s.server.stopProcessor(handle).toCompletableFuture();
            if (i < 4_090) continue;
            ControlReply r;
            try {
                r = reply.get(500, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException pending) {
                seen.add("pending");
                continue;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new AssertionError(e);
            }
            seen.add(r.outcome() + ":" + r.reason());
            if (String.valueOf(r.reason()).contains("mailbox full")) return new Busy(parker, handle);
        }
        throw new AssertionError("mailbox never filled; replies seen " + seen);
    }

    /** g. The same processor name in two groups. */
    static void duplicateNames(ScenarioServer s) {
        s.add("gA", "pricer", Fixtures.Healthy::new);
        s.add("gB", "pricer", Fixtures.Healthy::new);
        awaitTrue("both running", () -> s.server.controlGroupStatus("gA").state() == ControlDriver.GroupState.RUNNING
                && s.server.controlGroupStatus("gB").state() == ControlDriver.GroupState.RUNNING, 5_000);
    }
}
