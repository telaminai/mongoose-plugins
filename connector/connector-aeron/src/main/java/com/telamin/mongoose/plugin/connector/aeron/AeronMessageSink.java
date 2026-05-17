/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.aeron;

import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.output.AbstractMessageSink;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.agrona.BufferUtil;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aeron-backed message sink. Publishes each delivered event as a fragment on an
 * Aeron channel/stream. Strings and {@code byte[]} are sent verbatim; everything
 * else is stringified via {@link String#valueOf(Object)} and encoded UTF-8.
 * <p>
 * Either bring your own MediaDriver (recommended in production), or set
 * {@code launchEmbeddedDriver=true} for in-process / test setups.
 * <p>
 * Result-code policy on {@link Publication#offer}:
 * <ul>
 *   <li>{@code BACK_PRESSURED} / {@code ADMIN_ACTION} — retry until {@link #getOfferTimeoutNanos()}</li>
 *   <li>{@code NOT_CONNECTED} — retry; counted, but does not abort early</li>
 *   <li>{@code MAX_POSITION_EXCEEDED} — drop + warn (channel rotation needed)</li>
 *   <li>{@code CLOSED} — drop + warn (terminal)</li>
 * </ul>
 *
 * <p><b>Threading:</b> this sink is single-writer. Callers MUST serialise calls
 * to {@code sendToSink} — the Mongoose dispatcher already does this on the
 * sink's dispatch thread.
 */
@Log4j2
public class AeronMessageSink extends AbstractMessageSink<Object> implements Lifecycle {

    @Getter
    @Setter
    private String channel = "aeron:ipc";
    @Getter
    @Setter
    private int streamId = 10;
    @Getter
    @Setter
    private String aeronDirectoryName;
    @Getter
    @Setter
    private boolean launchEmbeddedDriver = false;
    @Getter
    @Setter
    private int initialBufferCapacity = 4096;
    @Getter
    @Setter
    private long offerTimeoutNanos = TimeUnit.SECONDS.toNanos(2);

    private MediaDriver mediaDriver;
    private Aeron aeron;
    private Publication publication;
    private UnsafeBuffer sendBuffer;

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong notConnectedRetries = new AtomicLong();

    @Override
    public void init() {
        if (channel == null || channel.isEmpty()) {
            throw new IllegalStateException("AeronMessageSink has no channel configured");
        }
        if (streamId <= 0) {
            throw new IllegalStateException("AeronMessageSink streamId must be > 0, got " + streamId);
        }
        if (initialBufferCapacity <= 0) {
            throw new IllegalStateException("initialBufferCapacity must be > 0");
        }
        sendBuffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(initialBufferCapacity, 64));
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
        publication = aeron.addPublication(channel, streamId);
        log.info("AeronMessageSink initialised channel:{} stream:{} dir:{} bufCap:{}",
                channel, streamId, aeronDirectoryName, initialBufferCapacity);
    }

    @Override
    protected void sendToSink(Object value) {
        if (publication == null || sendBuffer == null) {
            log.warn("AeronMessageSink not initialised — dropping event");
            dropped.incrementAndGet();
            return;
        }
        byte[] payload;
        if (value instanceof byte[] b) {
            payload = b;
        } else {
            payload = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }
        if (payload.length > sendBuffer.capacity()) {
            log.warn("Payload size {} exceeds send buffer capacity {} — dropping. Increase initialBufferCapacity.",
                    payload.length, sendBuffer.capacity());
            dropped.incrementAndGet();
            return;
        }
        sendBuffer.putBytes(0, payload);
        offerWithRetry(sendBuffer, payload.length);
    }

    private void offerWithRetry(DirectBuffer buffer, int length) {
        long deadline = System.nanoTime() + offerTimeoutNanos;
        while (true) {
            long result = publication.offer(buffer, 0, length);
            if (result > 0) {
                published.incrementAndGet();
                return;
            }
            if (result == Publication.CLOSED) {
                log.warn("Aeron publication CLOSED — dropping. channel:{} stream:{}", channel, streamId);
                dropped.incrementAndGet();
                return;
            }
            if (result == Publication.MAX_POSITION_EXCEEDED) {
                log.warn("Aeron publication MAX_POSITION_EXCEEDED — dropping. channel:{} stream:{}", channel, streamId);
                dropped.incrementAndGet();
                return;
            }
            if (result == Publication.NOT_CONNECTED) {
                notConnectedRetries.incrementAndGet();
            }
            if (System.nanoTime() > deadline) {
                log.warn("Aeron offer timed out after {}ns, last result:{} — dropping. channel:{} stream:{}",
                        offerTimeoutNanos, result, channel, streamId);
                dropped.incrementAndGet();
                return;
            }
            Thread.yield();
        }
    }

    public long getPublishedCount() {
        return published.get();
    }

    public long getDroppedCount() {
        return dropped.get();
    }

    public long getNotConnectedRetryCount() {
        return notConnectedRetries.get();
    }

    @Override
    public void tearDown() {
        try {
            if (publication != null) publication.close();
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
        publication = null;
        aeron = null;
        mediaDriver = null;
        sendBuffer = null;
    }
}
