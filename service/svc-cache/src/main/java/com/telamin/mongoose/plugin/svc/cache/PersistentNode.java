/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telamin.fluxtion.runtime.annotations.AfterEvent;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.mongoose.plugin.lib.json.StateJson;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * A node whose state survives a restart. Keep all runtime state in one record (or any Jackson-serialisable object)
 * returned by {@link #state()}: it is saved as JSON ({@link StateJson}: java.time values as ISO strings) to a
 * {@link Cache} service after every event and handed back to {@link #restore} on the next start. {@link #onCache} runs
 * first, for seeding configuration (see {@link Seed}).
 *
 * <p>Name the Cache service in the subclass (an annotation cannot be inherited with a name):
 * <pre>
 * &#64;ServiceRegistered("appState") &#64;Override public void cacheRegistered(Cache c) { super.cacheRegistered(c); }
 * </pre>
 *
 * <p>The saved record is {@code {"_v":3,"state":{...},"positions":{feed:{"epoch":e,"position":p}}}}. A record saved
 * as the bare state (no {@code _v}) or as {@code _v:2} (no epochs) is still restored; its positions are not trusted.
 *
 * <p><b>Crash safety.</b> With a Mongoose that provides {@code com.telamin.mongoose.service.delivery.DeliveryContext}
 * (at-least-once file feed delivery), a feed that commits its read position after processing re-delivers the last few
 * lines after a crash. The node saves each feed's source epoch and position in the same write as its state, and
 * {@link #replayed()} is true for an event whose effect the saved state already holds: start every handler that
 * changes state or publishes with {@code if (replayed()) return false;}. Use a {@link JsonFileCache} (its writes are
 * atomic) with {@code asyncWrite: false} (the default) and no agentGroup, so the state is written before the feed can
 * commit the event; an asynchronous write would let the feed commit run ahead of the saved state. With a Mongoose
 * without {@code DeliveryContext}, or an event that has no source position (a timer, an admin command),
 * {@code replayed()} is false and the state is saved in every event cycle. A skipped event is audited with
 * {@code replayedSkip} (the saved position), so the audit shows the cycle was a deliberate no-op.
 * {@code DeliveryContext} is looked up reflectively, once, so this class also runs on a Mongoose without it.
 *
 * <p><b>Saved once per delivery, when the delivery is complete.</b> A handler, or a sink it publishes to, may send the
 * processor another event with {@code onEvent} (or {@code EventDispatcher.queueReentrantEvent}) while it handles a
 * delivery: the generated processor runs {@code @AfterEvent} at the end of the first cycle and then the queued event,
 * as a second cycle under the same delivery. With Mongoose's delivery-complete hook ({@code DeliveryContext.onComplete},
 * Mongoose 1.1.3) the {@code @AfterEvent} of a positioned delivery does not write: it registers one callback for the
 * delivery, and the state and the delivery's position are written once, when the reader has finished the delivery,
 * queued events included, and before the feed can acknowledge or commit it. A queued event is new work and is applied
 * ({@code replayed()} is false for it). A crash before the callback leaves nothing of the delivery saved, so the
 * restart re-delivers it and the whole delivery, queued events included, is applied again. A save that fails in the
 * callback throws, and the reader retries it under its retry policy; if the policy gives up, the feed reports the loss
 * ({@code completionFailures}, {@code lossless:false}).
 *
 * <p><b>Supported processors: generated or interpreted.</b> A PersistentNode is supported in a generated or
 * interpreted processor. Such a processor that throws is quarantined by the server, never retried: quarantine is the
 * supported failure path, and nothing of the failed delivery is saved. A <b>handwritten</b> processor (an adapter
 * around a generated one) that throws part-way through a positioned delivery is retried by the server, and that is
 * <b>not supported</b>: the node's state saved after the retry is not guaranteed, because code that reads the node's
 * state before a guarded handler runs (an adapter computing an argument or choosing a branch) sees the failed
 * attempt's state. The node logs a WARN, once per node, the first time it sees such a retry
 * ({@code DeliveryContext.attempt()} grew); delivery is not changed. As a best effort for that case only, the node
 * restores its last saved record at the retry's first guarded handler (or {@code @AfterEvent}) and applies the
 * retry's cycles as new work, audited {@code retryRollback}.
 *
 * <p>On a Mongoose with {@code DeliveryContext} but without the hook (an intermediate 1.1.3 snapshot), a further cycle
 * under a delivery whose position this node already saved cannot be made crash-safe and is <b>refused</b>:
 * {@link #replayed()} and {@link #saveState()} throw {@link IllegalStateException} ({@code saveState} first writes the
 * node's state, so the cache still matches the node). Without {@code DeliveryContext} (1.1.2 and earlier) nothing
 * changes: no replay guard, a save in every cycle.
 *
 * <p><b>One PersistentNode per processor, or independent nodes only.</b> Each PersistentNode writes its own record,
 * so a processor with several of them writes the cache file once per node per event, and a crash between two nodes'
 * writes leaves them at different saved positions. On restart the re-delivered event is then {@code replayed()} for
 * the node that saved it and new for the one that did not; if the first is upstream and returns early
 * ({@code if (replayed()) return false;}), the downstream node never sees an event it still needs. Keep the replay
 * guard to a single PersistentNode per processor, or to nodes that do not trigger one another. Writes are not batched
 * into one per event cycle.
 *
 * @param <S> the state type
 */
@Log4j2
public abstract class PersistentNode<S> extends EventLogNode {

    private static final ObjectMapper JSON = StateJson.mapper();

    private transient Cache cache;
    /** Per feed, the source epoch and position of the last event whose effect is in the saved state. */
    private transient Map<String, Applied> appliedPositions = new HashMap<>();
    private transient DeliveryAccess delivery = DeliveryAccess.DEFAULT;
    /** Without the hook: the positioned delivery whose state this node last saved in this run; a further cycle is refused. */
    private transient Object savedDelivery;
    /** With the hook: the positioned delivery whose cycles this node is handling, saved at its completion. */
    private transient Object pendingDelivery;
    /** The dispatch attempt ({@code DeliveryContext.attempt()}) of the pending delivery this node last ran under. */
    private transient int cycleAttempt;
    /**
     * The node's state and positions before the pending delivery began, as a saved record: what the last save wrote
     * (or would have written, had the write failed). A retry of the delivery restores it (hook recheck F1).
     */
    private transient String baseline;
    /** The one completion callback of this node, so the delivery keeps it once however many cycles register it. */
    private final transient Runnable completion = this::saveAtCompletion;

    /** A source position within one epoch of the source (a new epoch: the pointer or the input file was reset). */
    public record Applied(long epoch, long position) {
    }

    /** The cache key for this node's state, e.g. "shop.basket". */
    protected abstract String stateKey();

    /** This node's current state. */
    protected abstract S state();

    /**
     * Called on start with the saved state, if there is one, and on a retry of a delivery with the state before it
     * (see the class notes): replace the node's state wholly, never merge.
     */
    protected abstract void restore(S saved);

    /** Called when the cache registers, before {@link #restore}; seed configuration here. */
    protected void onCache(Cache cache) {
    }

    /** The state type, from the generic parameter; override if it cannot be inferred. */
    @SuppressWarnings("unchecked")
    protected Class<S> stateType() {
        Type t = getClass().getGenericSuperclass();
        while (t instanceof Class<?> c) {
            t = c.getGenericSuperclass();
        }
        return (Class<S>) ((ParameterizedType) t).getActualTypeArguments()[0];
    }

    protected Cache cache() {
        return cache;
    }

    /** Override with {@code @ServiceRegistered("yourCacheServiceName")} and call super. */
    public void cacheRegistered(Cache cache) {
        this.cache = cache;
        onCache(cache);
        Object saved = cache.get(stateKey());
        if (saved instanceof String json && !json.isEmpty()) {
            try {
                JsonNode root = JSON.readTree(json);
                int version = root.path("_v").asInt();
                if (version >= 2) {
                    if (version >= 3) {   // a v2 record has no epoch, so its positions cannot be trusted to skip anything
                        root.path("positions").fields().forEachRemaining(e -> appliedPositions.put(e.getKey(),
                                new Applied(e.getValue().path("epoch").asLong(), e.getValue().path("position").asLong())));
                    }
                    restore(JSON.treeToValue(root.get("state"), stateType()));
                } else {
                    restore(JSON.treeToValue(root, stateType()));   // saved as the bare state: no positions
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot restore state '" + stateKey() + "' as " + stateType().getName(), e);
            }
        }
        baseline = record();   // the state before the first delivery: as restored, or as built
    }

    /**
     * True when the current event was re-delivered after a crash and its effect is already in the saved state.
     *
     * @throws IllegalStateException on a Mongoose without the delivery-complete hook, for a further event cycle under a
     *                               delivery whose state this node already saved in this run (see the class notes)
     */
    protected boolean replayed() {
        Object d = delivery.current();
        if (d == null || !(delivery.position(d) instanceof Long)) {
            return false;
        }
        if (delivery.hook()) {
            enterCycle(d);   // a retry rolls back first; then every cycle of the delivery is new work
        } else if (d.equals(savedDelivery)) {
            throw reentrant(d);
        }
        Applied stored = appliedPositions.get(delivery.feed(d));
        if (stored != null && delivery.alreadyApplied(d, stored.epoch(), stored.position())) {
            auditLog.info("replayedSkip", stored.position());   // the audit shows this cycle was a deliberate no-op
            return true;
        }
        return false;
    }

    @AfterEvent
    public void saveState() {
        if (cache == null) {
            return;
        }
        Object d = delivery.current();
        boolean positioned = d != null && delivery.position(d) instanceof Long;
        if (positioned && delivery.hook()) {
            // saved once the delivery, with the events it queued, is complete (see the class notes)
            enterCycle(d);
            if (delivery.onComplete(completion)) {
                return;
            }
            pendingDelivery = null;   // not a reader's delivery after all: save now
        }
        write(d, positioned);
        if (positioned && !delivery.hook()) {
            // written first, so the saved record matches this node's state; then refused (see class notes)
            if (d.equals(savedDelivery)) {
                throw reentrant(d);
            }
            savedDelivery = d;
        }
    }

    /**
     * The first sight of a cycle of positioned delivery {@code d}, from the handler's {@link #replayed()} or, for a
     * cycle in which no handler of this node ran, from {@code @AfterEvent}. A new delivery starts from the baseline; a
     * cycle of a later dispatch attempt of the same delivery (a retry: {@code DeliveryContext.attempt()} grew) first
     * restores the baseline, so the retry applies every cycle afresh and nothing of the failed attempt is counted twice
     * or skipped (hook recheck F1).
     */
    private void enterCycle(Object d) {
        int attempt = delivery.attempt();
        if (!d.equals(pendingDelivery)) {
            if (pendingDelivery != null) {
                // the previous delivery never completed (its dispatch failed and was given up on, or the processor
                // was removed): what is in memory now is what carries on
                baseline = record();
            }
            pendingDelivery = d;
            cycleAttempt = attempt;
            if (attempt > 0) {
                // first seen on a retry: the failed attempt never reached a guarded cycle of this node, so anything
                // it changed here is not rolled back (unsupported, warned)
                warnUnsupportedRetry(d);
            }
        } else if (attempt > cycleAttempt) {
            warnUnsupportedRetry(d);
            rollback();
            cycleAttempt = attempt;
        }
    }

    /** The unsupported-retry warning was logged for this node (once per node). */
    private transient boolean retryWarned;

    /** Once per node: a handwritten processor was retried part-way through a positioned delivery (not supported). */
    private void warnUnsupportedRetry(Object d) {
        if (retryWarned) {
            return;
        }
        retryWarned = true;
        log.warn("PersistentNode '{}': delivery {} is being retried (DeliveryContext.attempt() is {}): a handwritten"
                + " processor on this input threw and the server retries it, possibly not this node's own processor."
                + " That is not a supported configuration: the state saved after the retry is not guaranteed, because"
                + " code that reads or changes this node's state outside a guarded handler sees the failed attempt's"
                + " state. Host PersistentNodes in generated or interpreted processors, whose failures are"
                + " quarantined, not retried. Logged once per node", stateKey(), d, delivery.attempt());
    }

    /** For tests: whether the unsupported-retry warning was logged. */
    boolean retryWarningLogged() {
        return retryWarned;
    }

    /** Restore the state and positions of {@link #baseline}: the node as it was before the pending delivery. */
    private void rollback() {
        if (baseline == null) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(baseline);
            Map<String, Applied> positions = new HashMap<>();
            root.path("positions").fields().forEachRemaining(e -> positions.put(e.getKey(),
                    new Applied(e.getValue().path("epoch").asLong(), e.getValue().path("position").asLong())));
            appliedPositions = positions;
            restore(JSON.treeToValue(root.get("state"), stateType()));
            auditLog.info("retryRollback", (long) cycleAttempt);
        } catch (IOException e) {
            throw new IllegalStateException("cannot roll back state '" + stateKey() + "' for a retry", e);
        }
    }

    /** The delivery is complete: save the state with its position, once. */
    private void saveAtCompletion() {
        Object d = delivery.current();
        write(d, d != null && delivery.position(d) instanceof Long);
        pendingDelivery = null;
    }

    private void write(Object d, boolean positioned) {
        if (positioned) {
            long pos = (Long) delivery.position(d);
            // same epoch: keep the furthest position; a new epoch (source reset) starts over
            appliedPositions.merge(delivery.feed(d), new Applied(delivery.epoch(d), pos),
                    (old, now) -> old.epoch() == now.epoch() && old.position() >= now.position() ? old : now);
        }
        String record = record();
        baseline = record;          // what the next delivery starts from, whether or not the write below succeeds
        if (!positioned) {
            pendingDelivery = null; // a position-less save: nothing of a delivery is pending any more
        }
        cache.put(stateKey(), record);
    }

    /** The node's saved record: {@code {"_v":3,"state":...,"positions":...}}. */
    private String record() {
        try {
            ObjectNode record = JSON.createObjectNode();
            record.put("_v", 3);
            record.set("state", JSON.valueToTree(state()));
            record.set("positions", JSON.valueToTree(appliedPositions));
            return JSON.writeValueAsString(record);
        } catch (IOException e) {
            throw new IllegalStateException("cannot save state '" + stateKey() + "'", e);
        }
    }

    private IllegalStateException reentrant(Object d) {
        return new IllegalStateException("PersistentNode '" + stateKey() + "': a second event cycle under delivery "
                + d + ", whose position this node already saved. An event sent to the processor reentrantly (onEvent"
                + " or EventDispatcher from a handler or a sink) during a positioned delivery runs after the"
                + " delivery's @AfterEvent save and cannot be made crash-safe, so it is refused: do the work in the"
                + " same cycle, or publish it to a feed of its own");
    }

    /** For tests: the delivery context this node reads. */
    void deliveryAccess(DeliveryAccess access) {
        this.delivery = access;
    }

    /** For tests: the saved per-feed positions. */
    Map<String, Applied> appliedPositions() {
        return appliedPositions;
    }
}
