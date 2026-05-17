/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */


package com.telamin.mongoose.plugin.connector.kafka;

import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

@Log4j2
public class KafkaMessageConsumer extends AbstractAgentHostedEventSourceService<ConsumerRecords<?, ?>> {

    private Consumer<String, String> consumer;
    @Getter
    @Setter
    private Properties properties;
    @Getter
    @Setter
    private String[] topics;
    /**
     * Poll timeout per {@link #doWork()} call. Defaults to 100 ms — small enough
     * to keep agent-loop responsive, large enough to amortise broker round-trips.
     */
    @Getter
    @Setter
    private long pollTimeoutMs = 100L;
    /**
     * If true (default), {@link #tearDown()} calls {@code wakeup()} to unblock an
     * in-flight {@code poll()} so the agent thread can exit cleanly.
     */
    @Getter
    @Setter
    private boolean wakeupOnTearDown = true;

    protected KafkaMessageConsumer(String name) {
        super(name);
    }

    public KafkaMessageConsumer() {
        super("kafka-consumer");
    }

    @Override
    public void init() {
        if (properties == null) {
            throw new IllegalStateException("KafkaMessageConsumer properties must be set");
        }
        if (topics == null || topics.length == 0) {
            throw new IllegalStateException("KafkaMessageConsumer topics must be set");
        }
        log.info("Initializing KafkaMessageConsumer topics:{} props:{}", List.of(topics), properties);
    }

    @Override
    public void start() {
        log.info("Starting KafkaMessageConsumer topics:{}", List.of(topics));
        if (consumer == null) {
            consumer = new KafkaConsumer<>(properties);
        }
        consumer.subscribe(List.of(topics));
    }

    /**
     * Test hook: inject a consumer (e.g. {@code MockConsumer}) before {@link #start()}.
     */
    void setConsumer(Consumer<String, String> consumer) {
        this.consumer = consumer;
    }

    @Override
    public void tearDown() {
        if (consumer == null) return;
        try {
            if (wakeupOnTearDown) {
                consumer.wakeup();
            }
        } catch (Exception e) {
            log.warn("kafka consumer wakeup failed", e);
        }
        try {
            consumer.close();
        } catch (Exception e) {
            log.warn("kafka consumer close failed", e);
        } finally {
            consumer = null;
        }
    }

    @Override
    public int doWork() {
        if (consumer == null) return 0;
        ConsumerRecords<?, ?> records;
        try {
            records = consumer.poll(Duration.ofMillis(pollTimeoutMs));
        } catch (WakeupException e) {
            // teardown in progress; treat as no-work
            return 0;
        }
        if (records.isEmpty()) {
            return 0;
        }
        if (log.isDebugEnabled()) {
            for (ConsumerRecord<?, ?> record : records) {
                log.debug("partition:{} offset:{} key:{} value:{}",
                        record.partition(), record.offset(), record.key(), record.value());
            }
        }
        output.publish(records);
        return records.count();
    }
}
