package com.telamin.mongoose.plugin.testsupport.gym.node;

import com.telamin.mongoose.plugin.testsupport.gym.event.Join;
import com.telamin.mongoose.plugin.testsupport.gym.event.Payment;
import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.output.SinkPublisher;
import com.telamin.mongoose.plugin.svc.cache.Cache;
import com.telamin.mongoose.plugin.svc.cache.PersistentNode;
import java.util.HashMap;
import java.util.Map;

public class Membership extends PersistentNode<Membership.State> {

    public record Member(String name, String plan, double paid) {}

    public record State(Map<String, Member> members) {}

    private static final Map<String, Double> FEES = Map.of("MONTHLY", 50.0, "ANNUAL", 480.0);

    public SinkPublisher<Object> sinkAlerts = new SinkPublisher<>("alerts");
    private transient Map<String, Member> members = new HashMap<>();

    @ServiceRegistered("appState") @Override public void cacheRegistered(Cache c) { super.cacheRegistered(c); }
    @Override protected String stateKey() { return "membership"; }
    @Override protected State state() { return new State(members); }
    @Override protected void restore(State s) { if (s != null && s.members() != null) members = new HashMap<>(s.members()); }
    @Override protected void onCache(Cache cache) { }

    public Membership() {
    }

    @OnEventHandler
    public boolean onJoin(Join e) {
        if (replayed()) return false;
        String id = e.memberId();
        if (!FEES.containsKey(e.plan())) sinkAlerts.publish("ALERT membership JOIN " + id + ": unknown plan " + e.plan());
        else if (members.containsKey(id)) sinkAlerts.publish("ALERT membership JOIN " + id + ": already a member");
        else members.put(id, new Member(e.name(), e.plan(), 0));
        return false;
    }

    @OnEventHandler
    public boolean onPayment(Payment e) {
        if (replayed()) return false;
        String id = e.memberId();
        Member m = members.get(id);
        if (m == null) sinkAlerts.publish("ALERT membership PAY " + id + ": unknown member, payment of " + e.amount() + " not applied");
        else members.put(id, new Member(m.name(), m.plan(), m.paid() + e.amount()));
        return false;
    }

    public boolean isMember(String id) { return members.containsKey(id); }

    public boolean isActive(String id) {
        Member m = members.get(id);
        return m != null && m.paid() >= FEES.get(m.plan());
    }

    public double due(String id) {
        Member m = members.get(id);
        return m == null ? 0 : Math.max(0, FEES.get(m.plan()) - m.paid());
    }
}
