package com.telamin.mongoose.plugin.testsupport.gym.node;

import com.telamin.mongoose.plugin.testsupport.gym.event.CheckIn;
import com.telamin.mongoose.plugin.testsupport.gym.event.CheckOut;
import com.telamin.fluxtion.runtime.annotations.NoTriggerReference;
import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.output.SinkPublisher;
import com.telamin.mongoose.plugin.svc.cache.Cache;
import com.telamin.mongoose.plugin.svc.cache.PersistentNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FrontDesk extends PersistentNode<FrontDesk.State> {

    public record State(List<String> inside) {}

    private static final int CAPACITY = 2;

    public SinkPublisher<Object> sinkAlerts = new SinkPublisher<>("alerts");
    @NoTriggerReference private final Membership membership;
    private transient Set<String> inside = new LinkedHashSet<>();

    public FrontDesk(Membership membership) {
        this.membership = membership;
    }

    @ServiceRegistered("appState") @Override public void cacheRegistered(Cache c) { super.cacheRegistered(c); }
    @Override protected String stateKey() { return "frontDesk"; }
    @Override protected State state() { return new State(List.copyOf(inside)); }
    @Override protected void restore(State s) { if (s != null && s.inside() != null) inside = new LinkedHashSet<>(s.inside()); }
    @Override protected void onCache(Cache cache) { }

    @OnEventHandler
    public boolean onCheckIn(CheckIn e) {
        if (replayed()) return false;
        String id = e.memberId();
        if (!membership.isMember(id)) sinkAlerts.publish("ALERT access IN " + id + ": unknown member");
        else if (inside.contains(id)) sinkAlerts.publish("ALERT access IN " + id + ": already checked in");
        else if (!membership.isActive(id)) sinkAlerts.publish("ALERT access IN " + id + ": membership not active, balance due " + membership.due(id));
        else if (inside.size() >= CAPACITY) sinkAlerts.publish("ALERT access IN " + id + ": gym at capacity (" + inside.size() + "/" + CAPACITY + ")");
        else inside.add(id);
        return false;
    }

    @OnEventHandler
    public boolean onCheckOut(CheckOut e) {
        if (replayed()) return false;
        if (!inside.remove(e.memberId())) sinkAlerts.publish("ALERT access OUT " + e.memberId() + ": not checked in");
        return false;
    }
}
