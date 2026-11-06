package com.telamin.mongoose.plugin.testsupport.gym.node;

import com.telamin.mongoose.plugin.testsupport.gym.event.BookRoom;
import com.telamin.mongoose.plugin.testsupport.gym.event.CancelBooking;
import com.telamin.fluxtion.runtime.annotations.NoTriggerReference;
import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.output.SinkPublisher;
import com.telamin.mongoose.plugin.svc.cache.Cache;
import com.telamin.mongoose.plugin.svc.cache.PersistentNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class Bookings extends PersistentNode<Bookings.State> {

    public record Booking(String memberId, String room, String slot) {}

    public record State(Map<String, Booking> bookings) {}

    private static final Set<String> ROOMS = Set.of("studio-a", "studio-b", "spin-room");

    public SinkPublisher<Object> sinkAlerts = new SinkPublisher<>("alerts");
    @NoTriggerReference private final Membership membership;
    private transient Map<String, Booking> bookings = new LinkedHashMap<>();

    public Bookings(Membership membership) {
        this.membership = membership;
    }

    @ServiceRegistered("appState") @Override public void cacheRegistered(Cache c) { super.cacheRegistered(c); }
    @Override protected String stateKey() { return "bookings"; }
    @Override protected State state() { return new State(bookings); }
    @Override protected void restore(State s) { if (s != null && s.bookings() != null) bookings = new LinkedHashMap<>(s.bookings()); }
    @Override protected void onCache(Cache cache) { }

    @OnEventHandler
    public boolean onBookRoom(BookRoom e) {
        if (replayed()) return false;
        String b = e.bookingId();
        String id = e.memberId();
        String room = e.room();
        String slot = e.slot();
        if (bookings.containsKey(b)) sinkAlerts.publish("ALERT bookings BOOK " + b + ": duplicate booking id");
        else if (!ROOMS.contains(room)) sinkAlerts.publish("ALERT bookings BOOK " + b + ": unknown room " + room);
        else if (!membership.isMember(id)) sinkAlerts.publish("ALERT bookings BOOK " + b + ": unknown member " + id);
        else if (!membership.isActive(id)) sinkAlerts.publish("ALERT bookings BOOK " + b + ": member " + id + " not active, balance due " + membership.due(id));
        else {
            String holder = null;
            for (Map.Entry<String, Booking> entry : bookings.entrySet()) {
                if (entry.getValue().room().equals(room) && entry.getValue().slot().equals(slot)) {
                    holder = entry.getKey();
                    break;
                }
            }
            if (holder != null) sinkAlerts.publish("ALERT bookings BOOK " + b + ": " + room + " at " + slot + " already booked by " + holder);
            else bookings.put(b, new Booking(id, room, slot));
        }
        return false;
    }

    @OnEventHandler
    public boolean onCancelBooking(CancelBooking e) {
        if (replayed()) return false;
        if (bookings.remove(e.bookingId()) == null) sinkAlerts.publish("ALERT bookings CANCEL " + e.bookingId() + ": no such booking");
        return false;
    }
}
