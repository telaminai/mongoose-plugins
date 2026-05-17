/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.kafka;

import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.output.AbstractMessageSink;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.agrona.concurrent.Agent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Log4j2
public class KafkaMessagePublisher extends AbstractMessageSink<Object> implements Lifecycle, Agent {

    @Getter
    @Setter
    private Properties properties;
    @Getter
    @Setter
    private boolean flushEveryMessage = true;
    @Getter
    @Setter
    private String topic;
    /**
     * If true (default), a JVM shutdown hook is registered to flush and close the
     * producer on abrupt VM exit. Set to false if you manage lifecycle externally
     * (e.g. tests, container-managed shutdown).
     */
    @Getter
    @Setter
    private boolean registerShutdownHook = true;
    /**
     * Max time {@code close()} will wait for in-flight requests during teardown.
     * Defaults to 5 seconds — short enough to fit inside most container shutdown
     * grace periods, long enough to deliver the buffered batch.
     */
    @Getter
    @Setter
    private long closeTimeoutMs = 5_000L;

    private Producer<Object, Object> producer;
    private final AtomicBoolean flushMessageBuffer = new AtomicBoolean(false);
    private final AtomicLong sendCount = new AtomicLong();
    private final AtomicLong sendErrors = new AtomicLong();
    private Thread shutdownHook;

    @Override
    public void init() {
        if (topic == null || topic.isEmpty()) {
            throw new IllegalStateException("KafkaMessagePublisher topic must be set");
        }
        properties = properties == null ? new Properties() : properties;
        log.info("Initializing KafkaMessagePublisher topic:{} props:{}", topic, properties);
        if (producer == null) {
            producer = new KafkaProducer<>(properties);
        }
        if (registerShutdownHook) {
            shutdownHook = new Thread(this::flushAndClose, "kafka-publisher-shutdown-" + topic);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    @Override
    protected void sendToSink(Object value) {
        if (producer == null) {
            log.warn("KafkaMessagePublisher not initialised — dropping event");
            return;
        }
        ProducerRecord<Object, Object> producerRecord = new ProducerRecord<>(topic, value);
        producer.send(producerRecord, (metadata, exception) -> {
            if (exception != null) {
                sendErrors.incrementAndGet();
                log.warn("kafka send failed topic:{}", topic, exception);
            } else {
                sendCount.incrementAndGet();
            }
        });
        flushMessageBuffer.set(true);
        if (flushEveryMessage) {
            producer.flush();
        }
    }

    @Override
    public int doWork() {
        if (producer != null && flushMessageBuffer.getAndSet(false)) {
            producer.flush();
        }
        return 0;
    }

    @Override
    public String roleName() {
        return "kafka-publisher-" + topic;
    }

    public long getSendCount() {
        return sendCount.get();
    }

    public long getSendErrors() {
        return sendErrors.get();
    }

    /**
     * Test hook: inject a producer (e.g. {@code MockProducer}) before {@link #init()}.
     */
    void setProducer(Producer<Object, Object> producer) {
        this.producer = producer;
    }

    @Override
    public void tearDown() {
        flushAndClose();
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already in progress
            }
            shutdownHook = null;
        }
    }

    private synchronized void flushAndClose() {
        if (producer == null) return;
        try {
            producer.flush();
        } catch (Exception e) {
            log.warn("kafka flush failed", e);
        }
        try {
            producer.close(Duration.ofMillis(closeTimeoutMs));
            log.info("kafka producer closed topic:{} sent:{} errors:{}", topic, sendCount.get(), sendErrors.get());
        } catch (Exception e) {
            log.warn("kafka producer close failed", e);
        }
        producer = null;
    }
}
