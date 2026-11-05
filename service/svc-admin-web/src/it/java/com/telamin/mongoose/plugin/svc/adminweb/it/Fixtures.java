/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb.it;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import com.telamin.mongoose.service.audit.AuditCaptureResources;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic fault fixtures. Each one fails in exactly one way, on a named event, so a scenario
 * provokes one fact and nothing else.
 */
public final class Fixtures {

    private Fixtures() { }

    public static final String BOOM = "boom";
    public static final String BLOCK = "block";

    /** Every latch a fixture may park a thread on; released when a scenario closes its server. */
    private static final List<CountDownLatch> LATCHES = new CopyOnWriteArrayList<>();

    public static CountDownLatch latch() {
        CountDownLatch l = new CountDownLatch(1);
        LATCHES.add(l);
        return l;
    }

    public static void releaseAll() {
        LATCHES.forEach(CountDownLatch::countDown);
        LATCHES.clear();
    }

    static EventSubscriptionKey<Object> key(String feed) {
        return new EventSubscriptionKey<>(new EventSourceKey<>(feed), CallBackType.ON_EVENT_CALL_BACK);
    }

    /** A feed the test thread publishes into directly; the publisher's spin-then-drop path is the real one. */
    public static final class Feed implements EventFlowService<Object> {
        volatile EventToQueuePublisher<Object> publisher;
        @Override public void setEventToQueuePublisher(EventToQueuePublisher<Object> p) { publisher = p; }
        @Override public void subscribe(EventSubscriptionKey<Object> key) { }
        @Override public void unSubscribe(EventSubscriptionKey<Object> key) { }

        public void publish(Object event) { publisher.publish(event); }

        public int targets() {
            return publisher == null ? 0 : publisher.getTargetQueues().size();
        }

        public EventToQueuePublisher<Object> publisher() { return publisher; }
    }

    /** Base for handwritten processors: subscribes to {@code feed} on start. */
    public abstract static class Handwritten implements DataFlow {
        EventFeed subscription;
        public final AtomicInteger events = new AtomicInteger();
        @Override public void init() { }
        public void addEventFeed(EventFeed f) { subscription = f; }
        @Override public void start() { subscription.subscribe(this, key("feed")); }
        @Override public void stop() { }
        @Override public void tearDown() { }
    }

    /** a. A handwritten processor whose handler throws on {@code boom}. Ordinary retry behaviour applies. */
    public static final class ThrowingHandwritten extends Handwritten {
        @Override public void onEvent(Object event) {
            if (!(event instanceof String)) return;
            events.incrementAndGet();
            if (BOOM.equals(event)) throw new IllegalStateException("injected handwritten handler failure");
        }
    }

    /** A healthy peer, to show containment keeps the rest of the group running. */
    public static final class Healthy extends Handwritten {
        @Override public void onEvent(Object event) { if (event instanceof String) events.incrementAndGet(); }
    }

    /**
     * a. A GENERATED processor (an AOT-generated Fluxtion graph implementing the runtime's internal processor
     * interface, which is the containment marker) whose handler throws on {@code boom}. Mongoose's own
     * containment test uses the same generated class as its stand-in.
     */
    public static final class ThrowingGenerated extends GroupControlProcessor {
        EventFeed feed;
        public final AtomicInteger events = new AtomicInteger();
        public ThrowingGenerated() { init(); }
        @Override public void onEventInternal(Object event) {
            if (event instanceof String) {
                events.incrementAndGet();
                if (BOOM.equals(event)) throw new IllegalStateException("injected pricing failure in generated graph");
                return;
            }
            super.onEventInternal(event);
        }
        @Override public void addEventFeed(EventFeed feed) { super.addEventFeed(feed); this.feed = feed; }
        @Override public void startComplete() {
            super.startComplete();
            feed.subscribe(this, key("feed"));
        }
    }

    /** c. A processor whose {@code stop()} blocks until released. */
    public static final class BlockingStop extends Handwritten {
        public final CountDownLatch release = latch();
        public final CountDownLatch stopping = new CountDownLatch(1);
        @Override public void onEvent(Object event) { events.incrementAndGet(); }
        @Override public void stop() {
            stopping.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Agrona's closer interrupts; keep blocking until released so the stop stays unfinished
                try { release.await(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
        }
    }

    /** e/f. A processor whose handler parks the group thread on {@code block} until released. */
    public static final class Parker extends Handwritten {
        public final CountDownLatch release = latch();
        public final CountDownLatch parked = new CountDownLatch(1);
        @Override public void onEvent(Object event) {
            events.incrementAndGet();
            if (BLOCK.equals(event)) {
                parked.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /** d. Capture resources whose physical close really closes, then reports failure. */
    public static final class FailingCloseResources implements AuditCaptureResources {
        private final AuditCaptureResources delegate;
        public final AtomicInteger closes = new AtomicInteger();
        public FailingCloseResources(AuditCaptureResources delegate) { this.delegate = delegate; }
        @Override public Object openCapture(String processorName) throws Exception { return delegate.openCapture(processorName); }
        @Override public Object openCaptureScoped(AuditCaptureIdentity identity) throws Exception { return delegate.openCaptureScoped(identity); }
        @Override public void installCapture(Object resource, String processorName, DataFlow dataFlow) { delegate.installCapture(resource, processorName, dataFlow); }
        @Override public void uninstallCapture(Object resource, String processorName, DataFlow dataFlow) { delegate.uninstallCapture(resource, processorName, dataFlow); }
        @Override public void processorReleased(AuditCaptureIdentity identity, DataFlow flow) { delegate.processorReleased(identity, flow); }
        @Override public void closeCapture(Object resource) throws Exception {
            closes.incrementAndGet();
            try { delegate.closeCapture(resource); } catch (Exception ignored) { }
            throw new IllegalStateException("forced capture close failure");
        }
    }
}
