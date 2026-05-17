/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.connector.kafka;

import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class KafkaMessageConsumerTest {

    private static Properties consumerProps() {
        Properties p = new Properties();
        p.setProperty("bootstrap.servers", "localhost:0");
        p.setProperty("group.id", "test");
        p.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.setProperty("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        return p;
    }

    @Test
    void init_without_properties_throws() {
        KafkaMessageConsumer consumer = new KafkaMessageConsumer();
        consumer.setTopics(new String[]{"t"});
        Assertions.assertThrows(IllegalStateException.class, consumer::init);
    }

    @Test
    void init_without_topics_throws() {
        KafkaMessageConsumer consumer = new KafkaMessageConsumer();
        consumer.setProperties(consumerProps());
        Assertions.assertThrows(IllegalStateException.class, consumer::init);
    }

    @Test
    void mock_consumer_round_trip_publishes_records() {
        MockConsumer<String, String> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("t", 0);
        mock.subscribe(List.of("t"));
        mock.rebalance(List.of(tp));
        Map<TopicPartition, Long> beginning = new HashMap<>();
        beginning.put(tp, 0L);
        mock.updateBeginningOffsets(beginning);
        mock.addRecord(new ConsumerRecord<>("t", 0, 0L, "k1", "v1"));
        mock.addRecord(new ConsumerRecord<>("t", 0, 1L, "k2", "v2"));

        KafkaMessageConsumer consumer = new KafkaMessageConsumer();
        consumer.setProperties(consumerProps());
        consumer.setTopics(new String[]{"t"});
        consumer.init();
        consumer.setConsumer(mock);
        consumer.start();

        @SuppressWarnings({"unchecked", "rawtypes"})
        EventToQueuePublisher cast = new EventToQueuePublisher<>("kafka-test");
        try {
            java.lang.reflect.Field f = findField(consumer.getClass(), "output");
            f.setAccessible(true);
            f.set(consumer, cast);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        int polled = consumer.doWork();
        Assertions.assertEquals(2, polled, "two records polled");

        consumer.tearDown();
        Assertions.assertEquals(0, consumer.doWork(), "no work after teardown");
    }

    @Test
    void tear_down_without_start_is_safe() {
        KafkaMessageConsumer consumer = new KafkaMessageConsumer();
        Assertions.assertDoesNotThrow(consumer::tearDown);
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
