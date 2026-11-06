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
 * atomic) with {@code asyncWrite: false} (the default) and no agentGroup, so the state is written in the event cycle
 * that changed it; an asynchronous write would let the feed commit run ahead of the saved state. With a Mongoose
 * without {@code DeliveryContext}, or an event that has no source position (a timer, an admin command),
 * {@code replayed()} is false. A skipped event is audited with {@code replayedSkip} (the saved position), so the audit
 * shows the cycle was a deliberate no-op. {@code DeliveryContext} is looked up reflectively, once, so this class also
 * runs on a Mongoose without it.
 *
 * @param <S> the state type
 */
public abstract class PersistentNode<S> extends EventLogNode {

    private static final ObjectMapper JSON = StateJson.mapper();

    private transient Cache cache;
    /** Per feed, the source epoch and position of the last event whose effect is in the saved state. */
    private transient Map<String, Applied> appliedPositions = new HashMap<>();
    private transient DeliveryAccess delivery = DeliveryAccess.DEFAULT;

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

    /** True when the current event was re-delivered after a crash and its effect is already in the saved state. */
    protected boolean replayed() {
        Object d = delivery.current();
        if (d == null || !(delivery.position(d) instanceof Long)) {
            return false;
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
        if (cache != null) {
            try {
                Object d = delivery.current();
                if (d != null && delivery.position(d) instanceof Long pos) {
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
