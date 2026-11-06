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
 * restart re-delivers it and the whole delivery, queued events included, is applied again. A server retry of a
 * delivery whose cycle this node completed (the dispatch failed after it; {@code DeliveryContext.attempt()} grew) is
 * skipped as already applied in memory, audited {@code retriedSkip}, and saved at the retry's completion. A save that
 * fails in the callback throws, and the reader retries it under its retry policy.
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
public abstract class PersistentNode<S> extends EventLogNode {

    private static final ObjectMapper JSON = StateJson.mapper();

    private transient Cache cache;
    /** Per feed, the source epoch and position of the last event whose effect is in the saved state. */
    private transient Map<String, Applied> appliedPositions = new HashMap<>();
    private transient DeliveryAccess delivery = DeliveryAccess.DEFAULT;
    /** Without the hook: the positioned delivery whose state this node last saved in this run; a further cycle is refused. */
    private transient Object savedDelivery;
    /** With the hook: the positioned delivery whose cycle this node completed, saved at its completion. */
    private transient Object pendingDelivery;
    /** The dispatch attempt ({@code DeliveryContext.attempt()}) of the last cycle this node completed under it. */
    private transient int pendingAttempt;
    /** The one completion callback of this node, so the delivery keeps it once however many cycles register it. */
    private final transient Runnable completion = this::saveAtCompletion;

    /** A source position within one epoch of the source (a new epoch: the pointer or the input file was reset). */
    public record Applied(long epoch, long position) {
    }

    /** The cache key for this node's state, e.g. "shop.basket". */
    protected abstract String stateKey();

    /** This node's current state. */
    protected abstract S state();

    /** Called on start with the saved state, if there is one. */
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
            if (d.equals(pendingDelivery)) {
                if (delivery.attempt() > pendingAttempt) {
                    // a retry of the delivery after a failure: this node completed its cycle, so the effect is in
                    // memory, not yet saved; it is saved at the retry's completion
                    auditLog.info("retriedSkip", (long) (Long) delivery.position(d));
                    return true;
                }
                return false;   // a further cycle of the same attempt: an event the delivery queued, new work
            }
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
            pendingDelivery = d;
            pendingAttempt = delivery.attempt();
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

    /** The delivery is complete: save the state with its position, once. */
    private void saveAtCompletion() {
        Object d = delivery.current();
        write(d, d != null && delivery.position(d) instanceof Long);
        pendingDelivery = null;
    }

    private void write(Object d, boolean positioned) {
        try {
            if (positioned) {
                long pos = (Long) delivery.position(d);
                // same epoch: keep the furthest position; a new epoch (source reset) starts over
                appliedPositions.merge(delivery.feed(d), new Applied(delivery.epoch(d), pos),
                        (old, now) -> old.epoch() == now.epoch() && old.position() >= now.position() ? old : now);
            }
            ObjectNode record = JSON.createObjectNode();
            record.put("_v", 3);
            record.set("state", JSON.valueToTree(state()));
            record.set("positions", JSON.valueToTree(appliedPositions));
            cache.put(stateKey(), JSON.writeValueAsString(record));
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
