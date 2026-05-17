/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.server.plugin.connector.aeron;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sink round-trip over an embedded Aeron media driver using {@code aeron:ipc}.
 * Verifies both String and {@code byte[]} payload paths.
 */
class AeronMessageSinkTest {

    private MediaDriver mediaDriver;
    private Aeron aeron;

    private final String channel = "aeron:ipc";
    private final int streamId = 10;

    @BeforeEach
    void setUp() {
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
    }

    @AfterEach
    void tearDown() {
        try {
            if (aeron != null) aeron.close();
        } catch (Exception ignored) {
        }
        try {
            if (mediaDriver != null) mediaDriver.close();
        } catch (Exception ignored) {
        }
    }

    @Test
    void sends_string_and_bytes_over_ipc() throws Exception {
        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel(channel);
        sink.setStreamId(streamId);
        sink.setAeronDirectoryName(mediaDriver.aeronDirectoryName());
        sink.setLaunchEmbeddedDriver(false);
        sink.init();

        List<byte[]> received = new ArrayList<>();
        Subscription sub = aeron.addSubscription(channel, streamId);
        FragmentAssembler handler = new FragmentAssembler((buffer, offset, length, header) -> {
            byte[] data = new byte[length];
            buffer.getBytes(offset, data);
            received.add(data);
        });

        String s = "hello-aeron";
        invokeSend(sink, s);
        awaitReceive(sub, handler, received, 1, 2000);
        Assertions.assertEquals(s, new String(received.get(0), StandardCharsets.UTF_8));

        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        invokeSend(sink, payload);
        awaitReceive(sub, handler, received, 2, 2000);
        Assertions.assertArrayEquals(payload, received.get(1));

        sink.tearDown();
        sub.close();
    }

    @Test
    void init_with_empty_channel_throws() {
        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel("");
        Assertions.assertThrows(IllegalStateException.class, sink::init);
    }

    @Test
    void init_with_null_channel_throws() {
        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel(null);
        Assertions.assertThrows(IllegalStateException.class, sink::init);
    }

    @Test
    void init_with_non_positive_stream_id_throws() {
        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel(channel);
        sink.setStreamId(0);
        Assertions.assertThrows(IllegalStateException.class, sink::init);
        sink.setStreamId(-1);
        Assertions.assertThrows(IllegalStateException.class, sink::init);
    }

    @Test
    void init_with_non_positive_buffer_capacity_throws() {
        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel(channel);
        sink.setInitialBufferCapacity(0);
        Assertions.assertThrows(IllegalStateException.class, sink::init);
    }

    @Test
    void oversize_payload_is_dropped_and_counted() throws Exception {
        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel(channel);
        sink.setStreamId(streamId);
        sink.setAeronDirectoryName(mediaDriver.aeronDirectoryName());
        sink.setLaunchEmbeddedDriver(false);
        sink.setInitialBufferCapacity(16);
        sink.init();

        byte[] tooBig = new byte[32];
        invokeSend(sink, tooBig);

        Assertions.assertEquals(0, sink.getPublishedCount());
        Assertions.assertEquals(1, sink.getDroppedCount());

        sink.tearDown();
    }

    @Test
    void published_count_increments_on_success() throws Exception {
        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel(channel);
        sink.setStreamId(streamId);
        sink.setAeronDirectoryName(mediaDriver.aeronDirectoryName());
        sink.setLaunchEmbeddedDriver(false);
        sink.init();

        List<byte[]> received = new ArrayList<>();
        Subscription sub = aeron.addSubscription(channel, streamId);
        FragmentAssembler handler = new FragmentAssembler((buffer, offset, length, header) -> {
            byte[] data = new byte[length];
            buffer.getBytes(offset, data);
            received.add(data);
        });

        invokeSend(sink, "one");
        invokeSend(sink, "two");
        awaitReceive(sub, handler, received, 2, 2000);

        Assertions.assertEquals(2, sink.getPublishedCount());
        Assertions.assertEquals(0, sink.getDroppedCount());

        sink.tearDown();
        sub.close();
    }

    @Test
    void tear_down_is_idempotent() {
        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel(channel);
        sink.setStreamId(streamId);
        sink.setAeronDirectoryName(mediaDriver.aeronDirectoryName());
        sink.setLaunchEmbeddedDriver(false);
        sink.init();

        sink.tearDown();
        Assertions.assertDoesNotThrow(sink::tearDown);
    }

    // sendToSink is protected; call it through a package-private bridge.
    private static void invokeSend(AeronMessageSink sink, Object value) {
        new TestableAeronSink(sink).write(value);
    }

    private static class TestableAeronSink {
        private final AeronMessageSink delegate;

        TestableAeronSink(AeronMessageSink delegate) {
            this.delegate = delegate;
        }

        void write(Object value) {
            try {
                java.lang.reflect.Method m = AeronMessageSink.class.getDeclaredMethod("sendToSink", Object.class);
                m.setAccessible(true);
                m.invoke(delegate, value);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void awaitReceive(Subscription sub, FragmentAssembler handler, List<byte[]> list,
                              int expected, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            sub.poll(handler, 10);
            if (list.size() >= expected) return;
            Thread.sleep(5);
        }
        Assertions.fail("Timed out waiting for aeron messages: expected=" + expected + " got=" + list.size());
    }
}
