package com.telamin.mongoose.plugin.connector.aeron;

import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupCacheBoundaryTest {
    @Test
    void startupReplayEndsCachingButKeepsLiveDelivery() {
        var publisher = new EventToQueuePublisher<String>("startup-boundary");
        publisher.setCacheEventLog(true);
        var queue = new OneToOneConcurrentArrayQueue<Object>(32);
        publisher.addTargetQueue(queue, "subscriber");
        var source = new AeronArchiveEventSource();
        source.setOutput(publisher);
        publisher.cache("before");
        assertNull(queue.poll());

        source.startComplete();
        assertEquals("before", queue.poll());
        for (int i = 0; i < 100; i++) {
            publisher.publish("after-" + i);
            assertEquals("after-" + i, queue.poll());
        }
        assertEquals(1, source.eventLog().length, "live events must not grow the startup log");
        assertEquals("before", source.eventLog()[0].data());
        source.startComplete();
        assertNull(queue.poll(), "a repeated startup notification must not replay twice");
    }
}
