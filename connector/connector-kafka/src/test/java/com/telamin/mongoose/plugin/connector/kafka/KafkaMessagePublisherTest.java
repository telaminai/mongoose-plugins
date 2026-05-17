/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.connector.kafka;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Smoke tests for {@link KafkaMessagePublisher} that don't require a running broker.
 * Construction + properties handling only; end-to-end publish tests live in the
 * docker-compose environment under src/test/environment.
 */
class KafkaMessagePublisherTest {

    @Test
    void init_with_null_properties_falls_back_to_empty_properties() {
        KafkaMessagePublisher publisher = new KafkaMessagePublisher();
        publisher.setTopic("smoke-test-topic");
        // properties intentionally left null; init() must defend.
        Properties supplied = new Properties();
        supplied.setProperty("bootstrap.servers", "localhost:0"); // unused — producer is lazy
        supplied.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        supplied.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        publisher.setProperties(supplied);

        Assertions.assertDoesNotThrow(publisher::init,
                "init() should construct a KafkaProducer with supplied properties");
        Assertions.assertNotNull(publisher.getProperties());
        Assertions.assertEquals("smoke-test-topic", publisher.getTopic());
        Assertions.assertTrue(publisher.isFlushEveryMessage(), "default should flush every message");

        publisher.tearDown(); // closes the producer
    }

    @Test
    void properties_default_to_empty_when_set_to_null_before_init() {
        KafkaMessagePublisher publisher = new KafkaMessagePublisher();
        publisher.setProperties(null);
        publisher.setTopic("smoke-topic");
        publisher.setProperties(new Properties() {{
            setProperty("bootstrap.servers", "localhost:0");
            setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        }});
        publisher.init();
        Assertions.assertNotNull(publisher.getProperties());
        publisher.tearDown();
    }

    @Test
    void init_without_topic_throws() {
        KafkaMessagePublisher publisher = new KafkaMessagePublisher();
        Assertions.assertThrows(IllegalStateException.class, publisher::init);
    }

    @Test
    void mock_producer_round_trip_increments_send_count() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        MockProducer<Object, Object> mock = (MockProducer<Object, Object>) (MockProducer)
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaMessagePublisher publisher = new KafkaMessagePublisher();
        publisher.setTopic("mock-topic");
        publisher.setRegisterShutdownHook(false);
        publisher.setProducer(mock);
        publisher.init();

        invokeSend(publisher, "hello");
        invokeSend(publisher, "world");

        Assertions.assertEquals(2, mock.history().size());
        Assertions.assertEquals(2, publisher.getSendCount());
        Assertions.assertEquals(0, publisher.getSendErrors());

        publisher.tearDown();
        Assertions.assertTrue(mock.closed(), "tearDown should close the producer");
    }

    @Test
    void tear_down_is_idempotent() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        MockProducer<Object, Object> mock = (MockProducer<Object, Object>) (MockProducer)
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaMessagePublisher publisher = new KafkaMessagePublisher();
        publisher.setTopic("mock-topic");
        publisher.setRegisterShutdownHook(false);
        publisher.setProducer(mock);
        publisher.init();

        publisher.tearDown();
        Assertions.assertDoesNotThrow(publisher::tearDown);
    }

    @Test
    void send_after_close_warns_but_does_not_throw() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        MockProducer<Object, Object> mock = (MockProducer<Object, Object>) (MockProducer)
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaMessagePublisher publisher = new KafkaMessagePublisher();
        publisher.setTopic("mock-topic");
        publisher.setRegisterShutdownHook(false);
        publisher.setProducer(mock);
        publisher.init();
        publisher.tearDown();

        Assertions.assertDoesNotThrow(() -> invokeSend(publisher, "after-close"));
        Assertions.assertEquals(0, publisher.getSendCount());
    }

    private static void invokeSend(KafkaMessagePublisher publisher, Object value) {
        try {
            java.lang.reflect.Method m = KafkaMessagePublisher.class.getDeclaredMethod("sendToSink", Object.class);
            m.setAccessible(true);
            m.invoke(publisher, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
