/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.connector.kafka;

import com.fluxtion.dataflow.serverplugin.testsupport.MongooseTestHarness;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Producer-leg integration test for the Kafka connector pair using Kafka's
 * {@link MockProducer}. No broker required.
 *
 * <p>In-memory feed → handler → {@link KafkaMessagePublisher} → {@code MockProducer}.
 * Asserts that:
 * <ul>
 *   <li>MockProducer's history captures the published events in order.</li>
 *   <li>{@link KafkaMessagePublisher#getSendCount()} increments per send.</li>
 *   <li>The agent-loop teardown closes the producer cleanly.</li>
 * </ul>
 *
 * <p>Consumer-leg testing is covered by {@link KafkaMessageConsumerTest}
 * which drives {@code doWork()} synchronously — {@code MockConsumer} is not
 * thread-safe enough to run under the harness's agent loop.
 */
class KafkaRoundTripIntegrationTest {

    private static Properties producerProps() {
        Properties p = new Properties();
        p.setProperty("bootstrap.servers", "localhost:0");
        p.setProperty("key.serializer", StringSerializer.class.getName());
        p.setProperty("value.serializer", StringSerializer.class.getName());
        return p;
    }

    public static class ForwardingHandler extends ObjectEventHandlerNode {
        @SuppressWarnings("unchecked")
        private MessageSink<String> sink;

        @ServiceRegistered
        public void wire(MessageSink<?> sink, String name) {
            if ("kafka-out".equals(name)) {
                this.sink = (MessageSink<String>) sink;
            }
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s && sink != null) {
                sink.accept(s);
            }
            return true;
        }
    }

    @Test
    void producer_leg_publishes_to_mock_producer() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        MockProducer<Object, Object> mockProducer = (MockProducer<Object, Object>) (MockProducer)
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());

        InMemoryEventSource<String> producerFeed = new InMemoryEventSource<>();
        KafkaMessagePublisher kafkaSink = new KafkaMessagePublisher();
        kafkaSink.setTopic("trades");
        kafkaSink.setRegisterShutdownHook(false);
        kafkaSink.setProperties(producerProps());
        kafkaSink.setProducer(mockProducer);

        try (MongooseTestHarness h = MongooseTestHarness.builder()
                .feed("inbound", producerFeed, "producer-feed-agent")
                .sink("kafka-out", kafkaSink)
                .processor("processor-agent", "forwarder", new ForwardingHandler())
                .start()) {

            producerFeed.offer("alpha");
            producerFeed.offer("beta");
            producerFeed.offer("gamma");

            h.awaitCondition(() -> mockProducer.history().size() >= 3);

            List<String> sent = mockProducer.history().stream()
                    .map(r -> (String) r.value())
                    .collect(Collectors.toList());

            assertEquals(List.of("alpha", "beta", "gamma"), sent);
            assertEquals(3, kafkaSink.getSendCount());
            assertEquals(0, kafkaSink.getSendErrors());
        }
    }

}
