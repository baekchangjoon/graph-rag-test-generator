package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaCaptureReceiverTest {

    private static KafkaContainer kafka;
    private static String bootstrapServers;

    @BeforeAll
    static void setUpAll() throws Exception {
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));
        kafka.start();
        bootstrapServers = kafka.getBootstrapServers();

        // Pre-create all required topics for testing
        createTopic("test-topic");
        createTopic("__internal-topic");
        createTopic("caps-topic");
        createTopic("tombstone-topic");

        // Wait a bit to ensure metadata propagation
        Thread.sleep(1000);
    }

    @AfterAll
    static void tearDownAll() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    void testCapturesAndDrainsRecordByTraceId() throws Exception {
        String topic = "test-topic";
        createTopic(topic);

        KafkaCaptureReceiver receiver = new KafkaCaptureReceiver(bootstrapServers);
        receiver.start();

        try {
            String traceId = "123456789012345678901234567890ab";
            String spanId = "00f067aa0ba902b7";
            String traceparent = "00-" + traceId + "-" + spanId + "-01";

            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, "my-key", "{\"message\":\"hello\"}");
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
                producer.send(record).get();
            }

            List<KafkaCaptureReceiver.CapturedRecord> drained = receiver.drain(traceId, 5000);
            assertThat(drained).hasSize(1);
            assertThat(drained.get(0).topic()).isEqualTo(topic);
            assertThat(drained.get(0).key()).isEqualTo("my-key");
            assertThat(drained.get(0).value().get("message").asText()).isEqualTo("hello");
            assertThat(drained.get(0).headers()).containsEntry("traceparent", traceparent);
        } finally {
            receiver.close();
        }
    }

    @Test
    void testCapturesAndDrainsRecordByTraceIdCaseInsensitive() throws Exception {
        String topic = "test-topic";
        createTopic(topic);

        KafkaCaptureReceiver receiver = new KafkaCaptureReceiver(bootstrapServers);
        receiver.start();

        try {
            String traceId = "123456789012345678901234567890f5";
            String spanId = "00f067aa0ba902b9";
            String traceparent = "00-" + traceId + "-" + spanId + "-01";

            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, "my-key-case", "{\"message\":\"hello-case\"}");
                record.headers().add("TraceParent", traceparent.getBytes(StandardCharsets.UTF_8));
                producer.send(record).get();
            }

            List<KafkaCaptureReceiver.CapturedRecord> drained = receiver.drain(traceId, 5000);
            assertThat(drained).hasSize(1);
            assertThat(drained.get(0).topic()).isEqualTo(topic);
            assertThat(drained.get(0).key()).isEqualTo("my-key-case");
            assertThat(drained.get(0).value().get("message").asText()).isEqualTo("hello-case");
            assertThat(drained.get(0).headers()).containsEntry("TraceParent", traceparent);
        } finally {
            receiver.close();
        }
    }

    @Test
    void testFiltersOutInternalTopics() throws Exception {
        String internalTopic = "__internal-topic";
        createTopic(internalTopic);

        KafkaCaptureReceiver receiver = new KafkaCaptureReceiver(bootstrapServers);
        receiver.start();

        try {
            String traceId = "123456789012345678901234567890cd";
            String spanId = "00f067aa0ba902b8";
            String traceparent = "00-" + traceId + "-" + spanId + "-01";

            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                ProducerRecord<String, String> record = new ProducerRecord<>(internalTopic, "my-key", "{\"message\":\"hello\"}");
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
                producer.send(record).get();
            }

            List<KafkaCaptureReceiver.CapturedRecord> drained = receiver.drain(traceId, 1500);
            assertThat(drained).isEmpty();
        } finally {
            receiver.close();
        }
    }

    @Test
    void testCapsQueueToTenThousand() throws Exception {
        String topic = "test-topic";

        KafkaCaptureReceiver receiver = new KafkaCaptureReceiver(bootstrapServers);
        receiver.start();

        try {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                // 10005개의 메시지 발행 (각각 고유한 traceId)
                for (int i = 0; i < 10005; i++) {
                    String traceId = String.format("%032x", i);
                    String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";
                    ProducerRecord<String, String> record = new ProducerRecord<>(topic, "key-" + i, "{\"val\":" + i + "}");
                    record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
                    producer.send(record);
                }
                producer.flush();
            }

            // 대기하여 백그라운드 스레드가 다 수집하게 함
            Thread.sleep(3000);

            // 10,000개가 capped 큐 크기여야 함.
            // i = 0, 1, 2, 3, 4 (가장 오래된 5개)는 큐에서 evict되었어야 함.
            for (int i = 0; i < 5; i++) {
                String oldestTraceId = String.format("%032x", i);
                List<KafkaCaptureReceiver.CapturedRecord> drained = receiver.drain(oldestTraceId, 500);
                assertThat(drained).as("Record " + i + " should have been evicted").isEmpty();
            }

            // i = 10000, 10001, 10002, 10003, 10004 (가장 최신 5개)는 정상적으로 존재해야 함.
            for (int i = 10000; i < 10005; i++) {
                String newestTraceId = String.format("%032x", i);
                List<KafkaCaptureReceiver.CapturedRecord> drained = receiver.drain(newestTraceId, 500);
                assertThat(drained).as("Record " + i + " should be present").hasSize(1);
            }
        } finally {
            receiver.close();
        }
    }

    @Test
    void testHandlesNullTombstonesAndNonJsonPayloads() throws Exception {
        String topic = "test-topic";

        KafkaCaptureReceiver receiver = new KafkaCaptureReceiver(bootstrapServers);
        receiver.start();

        try {
            String traceId1 = "123456789012345678901234567890e1";
            String traceId2 = "123456789012345678901234567890e2";
            String traceparent1 = "00-" + traceId1 + "-00f067aa0ba902b7-01";
            String traceparent2 = "00-" + traceId2 + "-00f067aa0ba902b7-01";

            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                // 1. Null Tombstone
                ProducerRecord<String, String> record1 = new ProducerRecord<>(topic, "key-tombstone", null);
                record1.headers().add("traceparent", traceparent1.getBytes(StandardCharsets.UTF_8));
                producer.send(record1).get();

                // 2. Non-JSON Payload
                ProducerRecord<String, String> record2 = new ProducerRecord<>(topic, "key-nonjson", "plain text body");
                record2.headers().add("traceparent", traceparent2.getBytes(StandardCharsets.UTF_8));
                producer.send(record2).get();
            }

            // 1. Tombstone drain 검증
            List<KafkaCaptureReceiver.CapturedRecord> drained1 = receiver.drain(traceId1, 3000);
            assertThat(drained1).hasSize(1);
            // value가 null 인 경우 NullNode나 TextNode("") 등으로 처리
            assertThat(drained1.get(0).value()).isNotNull();
            
            // 2. Non-JSON drain 검증
            List<KafkaCaptureReceiver.CapturedRecord> drained2 = receiver.drain(traceId2, 3000);
            assertThat(drained2).hasSize(1);
            assertThat(drained2.get(0).value()).isInstanceOf(TextNode.class);
            assertThat(drained2.get(0).value().asText()).isEqualTo("plain text body");
        } finally {
            receiver.close();
        }
    }

    @Test
    void testSettleTimeoutAllowsAdditionalEvents() throws Exception {
        String topic = "test-topic";
        createTopic(topic);

        KafkaCaptureReceiver receiver = new KafkaCaptureReceiver(bootstrapServers);
        receiver.start();

        try {
            String traceId = "123456789012345678901234567890cc";
            String spanId1 = "00f067aa0ba902b1";
            String spanId2 = "00f067aa0ba902b2";
            String traceparent1 = "00-" + traceId + "-" + spanId1 + "-01";
            String traceparent2 = "00-" + traceId + "-" + spanId2 + "-01";

            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                // 1. First event
                ProducerRecord<String, String> record1 = new ProducerRecord<>(topic, "key-1", "{\"val\":1}");
                record1.headers().add("traceparent", traceparent1.getBytes(StandardCharsets.UTF_8));
                producer.send(record1).get();
            }

            // 2. Call drain. We send the second event after a short delay (40ms) using a background thread, while the main thread is blocked in drain.
            Thread asyncSender = new Thread(() -> {
                try {
                    Thread.sleep(40);
                    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                        ProducerRecord<String, String> record2 = new ProducerRecord<>(topic, "key-2", "{\"val\":2}");
                        record2.headers().add("traceparent", traceparent2.getBytes(StandardCharsets.UTF_8));
                        producer.send(record2).get();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            asyncSender.start();

            // Timeout of 2000ms is enough. Settle timeout of 100ms should easily capture the second event sent at 40ms.
            List<KafkaCaptureReceiver.CapturedRecord> drained = receiver.drain(traceId, 2000);
            asyncSender.join();

            // With the settle timeout, both events should be captured because the second event arrived within 100ms of the first event.
            // Without the settle timeout, the drain method returns immediately after finding the first event, resulting in size 1.
            assertThat(drained).hasSize(2);
            assertThat(drained.get(0).key()).isEqualTo("key-1");
            assertThat(drained.get(1).key()).isEqualTo("key-2");
        } finally {
            receiver.close();
        }
    }

    private static void createTopic(String topic) throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(Collections.singletonList(new NewTopic(topic, 1, (short) 1))).all().get();
        } catch (Exception e) {
            // Topic might already exist
        }
    }
}
