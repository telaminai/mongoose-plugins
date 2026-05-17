/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.aeron;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import io.aeron.driver.MediaDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Live-mode round-trip: {@link AeronMessageSink} → embedded media driver →
 * {@link AeronArchiveEventSource}. Asserts pre-{@code startComplete} events are
 * cached, and post-{@code startComplete} events also land in the event log.
 */
class AeronArchiveEventSourceTest {

    private MediaDriver mediaDriver;

    @BeforeEach
    void setUp() {
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
    }

    @AfterEach
    void tearDown() {
        try {
            if (mediaDriver != null) mediaDriver.close();
        } catch (Exception ignored) {
        }
    }

    @Test
    void start_with_empty_channel_throws() {
        AeronArchiveEventSource source = new AeronArchiveEventSource();
        source.setChannel("");
        source.setOutput(new EventToQueuePublisher<>("aeron-bad"));
        Assertions.assertThrows(IllegalStateException.class, source::onStart);
    }

    @Test
    void start_with_non_positive_stream_id_throws() {
        AeronArchiveEventSource source = new AeronArchiveEventSource();
        source.setChannel("aeron:ipc");
        source.setStreamId(0);
        source.setOutput(new EventToQueuePublisher<>("aeron-bad"));
        Assertions.assertThrows(IllegalStateException.class, source::onStart);
    }

    @Test
    void start_with_non_positive_fragment_limit_throws() {
        AeronArchiveEventSource source = new AeronArchiveEventSource();
        source.setChannel("aeron:ipc");
        source.setStreamId(10);
        source.setFragmentLimit(0);
        source.setOutput(new EventToQueuePublisher<>("aeron-bad"));
        Assertions.assertThrows(IllegalStateException.class, source::onStart);
    }

    @Test
    void binary_mode_publishes_byte_arrays() throws Exception {
        AeronArchiveEventSource source = new AeronArchiveEventSource();
        source.setMode(AeronArchiveEventSource.Mode.LIVE);
        source.setChannel("aeron:ipc");
        source.setStreamId(11);
        source.setAeronDirectoryName(mediaDriver.aeronDirectoryName());
        source.setLaunchEmbeddedDriver(false);
        source.setBinaryMode(true);
        source.setCacheEventLog(true);
        source.setOutput(new EventToQueuePublisher<>("aeron-binary"));
        source.onStart();

        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel("aeron:ipc");
        sink.setStreamId(11);
        sink.setAeronDirectoryName(mediaDriver.aeronDirectoryName());
        sink.setLaunchEmbeddedDriver(false);
        sink.init();

        byte[] payload = new byte[]{9, 8, 7, 6, 5};
        invokeSend(sink, payload);

        long end = System.currentTimeMillis() + 2000;
        boolean got = false;
        while (System.currentTimeMillis() < end && !got) {
            source.doWork();
            for (NamedFeedEvent<?> ev : source.eventLog()) {
                Object data = ev.data();
                if (data instanceof byte[] b && Arrays.equals(b, payload)) {
                    got = true;
                    break;
                }
            }
            Thread.sleep(5);
        }
        Assertions.assertTrue(got, "expected byte[] payload echoed back in event log");

        sink.tearDown();
        source.tearDown();
    }

    @Test
    void live_mode_caches_then_publishes() throws Exception {
        AeronArchiveEventSource source = new AeronArchiveEventSource();
        source.setMode(AeronArchiveEventSource.Mode.LIVE);
        source.setChannel("aeron:ipc");
        source.setStreamId(10);
        source.setAeronDirectoryName(mediaDriver.aeronDirectoryName());
        source.setLaunchEmbeddedDriver(false);
        source.setCacheEventLog(true);
        source.setOutput(new EventToQueuePublisher<>("aeron-live"));
        source.onStart();

        AeronMessageSink sink = new AeronMessageSink();
        sink.setChannel("aeron:ipc");
        sink.setStreamId(10);
        sink.setAeronDirectoryName(mediaDriver.aeronDirectoryName());
        sink.setLaunchEmbeddedDriver(false);
        sink.init();

        invokeSend(sink, "pre1");
        invokeSend(sink, "pre2");

        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            source.doWork();
            List<String> cachedNow = Arrays.stream(source.eventLog())
                    .map(NamedFeedEvent::data)
                    .map(Object::toString)
                    .collect(Collectors.toList());
            if (cachedNow.containsAll(List.of("pre1", "pre2"))) {
                break;
            }
            Thread.sleep(5);
        }

        List<String> cached = Arrays.stream(source.eventLog())
                .map(NamedFeedEvent::data)
                .map(Object::toString)
                .collect(Collectors.toList());
        Assertions.assertTrue(cached.containsAll(List.of("pre1", "pre2")),
                "expected pre-startComplete events cached, got: " + cached);

        source.startComplete();
        invokeSend(sink, "post1");
        invokeSend(sink, "post2");

        long end2 = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < end2) {
            source.doWork();
            Thread.sleep(5);
        }

        List<String> cachedAfter = Arrays.stream(source.eventLog())
                .map(NamedFeedEvent::data)
                .map(Object::toString)
                .collect(Collectors.toList());
        Assertions.assertTrue(cachedAfter.contains("post1") && cachedAfter.contains("post2"),
                "expected post-startComplete events in log, got: " + cachedAfter);

        sink.tearDown();
        source.tearDown();
    }

    private static void invokeSend(AeronMessageSink sink, Object value) {
        try {
            Method m = AeronMessageSink.class.getDeclaredMethod("sendToSink", Object.class);
            m.setAccessible(true);
            m.invoke(sink, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
