/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.aeron;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Aeron-backed event source. Subscribes to an Aeron channel/stream and publishes
 * each received fragment as either a UTF-8 {@code String} or {@code byte[]}
 * (see {@link #setBinaryMode(boolean)}) into the Mongoose dispatch pipeline.
 * <p>
 * Two modes:
 * <ul>
 *   <li>{@link Mode#LIVE} — subscribe to the live channel.</li>
 *   <li>{@link Mode#ARCHIVE} — find the most recent recording for the channel/stream
 *       in an Aeron Archive and replay it; useful for cold-start replay scenarios.</li>
 * </ul>
 * <p>
 * Either bring your own MediaDriver (recommended in production), or set
 * {@code launchEmbeddedDriver=true} for in-process / test setups.
 */
@Log4j2
public class AeronArchiveEventSource extends AbstractAgentHostedEventSourceService {

    public enum Mode {LIVE, ARCHIVE}

    @Getter
    @Setter
    private Mode mode = Mode.LIVE;
    @Getter
    @Setter
    private String channel = "aeron:ipc";
    @Getter
    @Setter
    private int streamId = 10;
    @Getter
    @Setter
    private String replayChannel = "aeron:ipc";
    @Getter
    @Setter
    private String aeronDirectoryName;
    @Getter
    @Setter
    private boolean launchEmbeddedDriver = false;
    @Getter
    @Setter
    private boolean cacheEventLog = false;
    /**
     * If true, publish raw {@code byte[]} payloads. If false (default), decode each
     * fragment as a UTF-8 {@link String}.
     */
    @Getter
    @Setter
    private boolean binaryMode = false;
    /**
     * Max fragments polled per {@link #doWork()} call. Higher = more throughput per
     * loop iteration; lower = tighter latency under co-located agents.
     */
    @Getter
    @Setter
    private int fragmentLimit = 50;

    private volatile boolean publishToQueue = false;

    private MediaDriver mediaDriver;
    private Aeron aeron;
    private Subscription subscription;
    private AeronArchive archive;

    private final FragmentAssembler fragmentAssembler = new FragmentAssembler((buffer, offset, length, header) -> {
        byte[] data = new byte[length];
        buffer.getBytes(offset, data);
        Object event = binaryMode ? data : new String(data, StandardCharsets.UTF_8);
        publish(event);
    });

    public AeronArchiveEventSource() {
        this("aeron-archive-event-source");
    }

    public AeronArchiveEventSource(String name) {
        super(name);
    }

    @Override
    public void onStart() {
        if (channel == null || channel.isEmpty()) {
            throw new IllegalStateException(
                    "AeronArchiveEventSource " + serviceName + " has no channel configured");
        }
        if (streamId <= 0) {
            throw new IllegalStateException(
                    "AeronArchiveEventSource " + serviceName + " streamId must be > 0, got " + streamId);
        }
        if (fragmentLimit <= 0) {
            throw new IllegalStateException(
                    "AeronArchiveEventSource " + serviceName + " fragmentLimit must be > 0, got " + fragmentLimit);
        }
        if (launchEmbeddedDriver) {
            MediaDriver.Context ctx = new MediaDriver.Context()
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true);
            mediaDriver = MediaDriver.launchEmbedded(ctx);
            if (aeronDirectoryName == null || aeronDirectoryName.isEmpty()) {
                aeronDirectoryName = mediaDriver.aeronDirectoryName();
            }
        }
        Aeron.Context aCtx = new Aeron.Context();
        if (aeronDirectoryName != null && !aeronDirectoryName.isEmpty()) {
            aCtx.aeronDirectoryName(aeronDirectoryName);
        }
        aeron = Aeron.connect(aCtx);

        output.setCacheEventLog(cacheEventLog);

        if (mode == Mode.LIVE) {
            subscription = aeron.addSubscription(channel, streamId);
            log.info("AeronArchiveEventSource live subscription channel:{} stream:{}", channel, streamId);
        } else {
            archive = AeronArchive.connect(new AeronArchive.Context().aeron(aeron));
            long recordingId = findLatestRecordingId(archive, channel, streamId);
            if (recordingId == Aeron.NULL_VALUE) {
                log.warn("No recording found for channel:{} stream:{} — archive mode will be idle", channel, streamId);
            } else {
                long replaySession = archive.startReplay(recordingId, 0, Long.MAX_VALUE, replayChannel, streamId);
                subscription = aeron.addSubscription(replayChannel, streamId);
                log.info("Started archive replay recordingId:{} session:{} on {} stream:{}",
                        recordingId, replaySession, replayChannel, streamId);
            }
        }

        if (cacheEventLog) {
            doWork();
        }
    }

    private static long findLatestRecordingId(AeronArchive archive, String channel, int streamId) {
        final long[] latest = {Aeron.NULL_VALUE};
        archive.listRecordingsForUri(0, Integer.MAX_VALUE, channel, streamId,
                (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, streamIdMatched, strippedChannel, originalChannel, sourceIdentity) -> {
                    if (latest[0] == Aeron.NULL_VALUE || recordingId > latest[0]) {
                        latest[0] = recordingId;
                    }
                });
        return latest[0];
    }

    @Override
    public void startComplete() {
        publishToQueue = true;
        output.dispatchCachedEventLog();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public NamedFeedEvent<?>[] eventLog() {
        List<NamedFeedEvent> eventLog = output.getEventLog();
        return eventLog.toArray(new NamedFeedEvent[0]);
    }

    @Override
    public int doWork() {
        if (subscription == null) return 0;
        return subscription.poll(fragmentAssembler, fragmentLimit);
    }

    private void publish(Object event) {
        if (publishToQueue) {
            output.publish(event);
        } else {
            output.cache(event);
        }
    }

    @Override
    public void tearDown() {
        try {
            if (subscription != null) subscription.close();
        } catch (Exception ignored) {
        }
        try {
            if (archive != null) archive.close();
        } catch (Exception ignored) {
        }
        try {
            if (aeron != null) aeron.close();
        } catch (Exception ignored) {
        }
        try {
            if (mediaDriver != null) mediaDriver.close();
        } catch (Exception ignored) {
        }
        subscription = null;
        archive = null;
        aeron = null;
        mediaDriver = null;
    }

    // for testing
    void setOutput(EventToQueuePublisher<?> eventToQueue) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        EventToQueuePublisher cast = eventToQueue;
        this.output = cast;
    }
}
