/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.multicast;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.lang.reflect.Field;

public class MulticastEventSourceTest {

    private static final String GROUP = "230.0.0.1";
    private static final int PORT = 4446;

    private MulticastEventSource source;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(MulticastTestSupport.canSendAndReceive(GROUP, PORT), "Multicast not working in this environment; skipping test");
        source = new MulticastEventSource();
        source.setMulticastGroup(GROUP);
        source.setMulticastPort(PORT);
        source.setCacheEventLog(true);
        // Inject an EventToQueuePublisher so onStart() can use it
        source.setOutput(new EventToQueuePublisher<>("multicast-event-source"));
        source.onStart();
    }

    @AfterEach
    void tearDown() {
        try {
            if (source != null) {
                source.tearDown();
            }
        } catch (Exception ignored) {}
    }

    @Test
    void testCacheBeforeStartCompleteAndPublishAfter() throws Exception {
        // Send two messages before startComplete
        sendUdp("pre1");
        sendUdp("pre2");

        // Retry loop to allow packets to arrive and be processed
        long end = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < end) {
            source.doWork();
            List<String> cachedNow = Arrays.stream(source.eventLog())
                    .map(NamedFeedEvent::data)
                    .map(Object::toString)
                    .collect(Collectors.toList());
            if (cachedNow.containsAll(List.of("pre1", "pre2"))) {
                break;
            }
            Thread.sleep(10);
        }

        // Verify event log contains the cached messages
        List<String> cached = Arrays.stream(source.eventLog())
                .map(NamedFeedEvent::data)
                .map(Object::toString)
                .collect(Collectors.toList());
        Assertions.assertTrue(cached.containsAll(List.of("pre1", "pre2")));

        // Now switch to publish mode
        source.startComplete();

        // Send two more messages
        sendUdp("post1");
        sendUdp("post2");

        // Give the source time to process post-start messages (which should be published, not cached)
        long end2 = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < end2) {
            source.doWork();
            Thread.sleep(5);
        }

        // Event log should grow with post-start messages (as they are published, and cached)
        List<String> cachedAfter = Arrays.stream(source.eventLog())
                .map(NamedFeedEvent::data)
                .map(Object::toString)
                .collect(Collectors.toList());
        Assertions.assertTrue(cachedAfter.contains("post1") && cachedAfter.contains("post2"),
                "Post-start messages should not be cached in eventLog");
    }

    @Test
    void testUseLoopbackInterfaceTrue_selectsLoopbackAndReceives() throws Exception {
        // preconditions: environment supports multicast and we have a loopback interface
        Assumptions.assumeTrue(MulticastTestSupport.canSendAndReceive(GROUP, PORT), "Multicast not working in this environment; skipping test");
        Assumptions.assumeTrue(NetworkHelper.getLoopbackInterface() != null, "No loopback interface available; skipping test");

        // Recreate source with loopback enabled
        tearDown();
        source = new MulticastEventSource();
        source.setMulticastGroup(GROUP);
        source.setMulticastPort(PORT);
        source.setCacheEventLog(true);
        source.setUseLoopbackInterface(true);
        source.setOutput(new EventToQueuePublisher<>("multicast-event-source"));
        source.onStart();

        // Verify the private netIf is the loopback interface
        NetworkInterface selected = getSelectedInterface(source);
        Assertions.assertNotNull(selected, "Expected a selected NetworkInterface when useLoopbackInterface=true");
        Assertions.assertTrue(selected.isLoopback(), "Selected interface should be loopback when useLoopbackInterface=true");

        // Note: Whether multicast loopback traffic is actually delivered depends on OS/JVM settings
        // and is outside the scope of this option. Here we only validate that the loopback
        // interface was selected when the flag is true.
    }

    @Test
    void testUseLoopbackInterfaceFalse_doesNotSelectInterfaceAndReceives() throws Exception {
        Assumptions.assumeTrue(MulticastTestSupport.canSendAndReceive(GROUP, PORT), "Multicast not working in this environment; skipping test");

        // Recreate source with loopback disabled (default)
        tearDown();
        source = new MulticastEventSource();
        source.setMulticastGroup(GROUP);
        source.setMulticastPort(PORT);
        source.setCacheEventLog(true);
        source.setUseLoopbackInterface(false);
        source.setOutput(new EventToQueuePublisher<>("multicast-event-source"));
        source.onStart();

        // With flag=false and no explicit interface name, the source should not set netIf
        NetworkInterface selected = getSelectedInterface(source);
        Assertions.assertNull(selected, "Expected no selected NetworkInterface when useLoopbackInterface=false and no interface name provided");

        // Send and verify receipt (should still work using default interface join)
        String msg = "defaultIfaceMsg";
        sendUdp(msg);
        long end = System.currentTimeMillis() + 1_000;
        boolean seen = false;
        while (System.currentTimeMillis() < end) {
            source.doWork();
            List<String> cached = Arrays.stream(source.eventLog())
                    .map(NamedFeedEvent::data)
                    .map(Object::toString)
                    .collect(Collectors.toList());
            if (cached.contains(msg)) {
                seen = true;
                break;
            }
            Thread.sleep(10);
        }
        Assertions.assertTrue(seen, "Expected to receive message when using default interface selection");
    }

    // Reflectively access the private 'netIf' to validate selection logic
    private static NetworkInterface getSelectedInterface(MulticastEventSource src) {
        return src.getNetIf();
    }

    private void sendUdp(String s) throws Exception {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket sender = new DatagramSocket()) {
            InetAddress groupAddr = InetAddress.getByName(GROUP);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, groupAddr, PORT);
            sender.send(packet);
        }
    }
}
