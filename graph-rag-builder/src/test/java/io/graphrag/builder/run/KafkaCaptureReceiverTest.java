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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;

// 테스트 격리: testCapsQueueToTenThousand이 공유 토픽에 1만건을 flood하면 이후 테스트의 리시버(earliest,
// 모든 토픽 구독)가 그 backlog를 읽어 대상 레코드를 굶기거나 cap-evict해 flaky 실패가 난다.
// flood 테스트를 @Order로 맨 마지막에 돌려 이후 테스트가 backlog에 노출되지 않게 한다(결정적, 순차 실행).
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("docker")
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
    @Order(Integer.MAX_VALUE)   // run LAST: its 10k flood pollutes the shared topic for earliest-readers
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

            // 단일 producer를 미리 warm해서 두 send 모두 빠르게 만든다. (이전엔 background 스레드가
            // 40ms settle window 안에서 새 KafkaProducer를 *생성*했는데, CI에서 producer 생성/메타데이터
            // fetch가 100ms settle을 넘겨 두 번째 이벤트를 놓치는 flaky의 근본 원인이었다.)
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                // 0. consumer가 group join/assignment을 끝내고 실제로 소비할 때까지 warmup.
                //    drain의 first-match 윈도우는 200ms로 짧아, 갓 start()한 consumer의 rebalance가
                //    그 안에 안 끝나면 event1을 못 잡아 size 0이 된다(CI 근본 원인). 이 helper는
                //    consumer가 살아 소비할 때까지 probe를 보내 확인해 타이밍 의존을 제거한다.
                awaitReceiverConsuming(receiver, producer, topic);

                // 1. First event (이 send가 producer 메타데이터까지 warm한다)
                ProducerRecord<String, String> record1 = new ProducerRecord<>(topic, "key-1", "{\"val\":1}");
                record1.headers().add("traceparent", traceparent1.getBytes(StandardCharsets.UTF_8));
                producer.send(record1).get();

                // 2. Call drain. Send the second event after a short delay (40ms) on a background thread
                // using the SAME warmed producer — window 안에서는 send만 하므로 100ms settle에 안정적으로 든다.
                Thread asyncSender = new Thread(() -> {
                    try {
                        Thread.sleep(40);
                        ProducerRecord<String, String> record2 = new ProducerRecord<>(topic, "key-2", "{\"val\":2}");
                        record2.headers().add("traceparent", traceparent2.getBytes(StandardCharsets.UTF_8));
                        producer.send(record2).get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
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
            }
        } finally {
            receiver.close();
        }
    }

    /**
     * receiver의 consumer가 실제로 레코드를 소비할 수 있을 때까지(=group join/assignment 완료) 대기한다.
     * 별도 traceId의 probe 레코드를 반복 발행하고, drain으로 그게 잡히면 consumer가 살아있다고 본다.
     * (consumer는 earliest라 assignment 후 이전 probe까지 모두 읽으므로 한 번이라도 잡히면 준비 완료.)
     */
    private static void awaitReceiverConsuming(KafkaCaptureReceiver receiver,
                                               KafkaProducer<String, String> producer,
                                               String topic) throws Exception {
        String warmTrace = "ffffffffffffffffffffffffffffffff";
        String warmTp = "00-" + warmTrace + "-00f067aa0ba90001-01";
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ProducerRecord<String, String> probe = new ProducerRecord<>(topic, "warmup", "{}");
            probe.headers().add("traceparent", warmTp.getBytes(StandardCharsets.UTF_8));
            producer.send(probe).get();
            if (!receiver.drain(warmTrace, 1000).isEmpty()) {
                return;
            }
        }
        throw new IllegalStateException("Kafka capture receiver did not start consuming within 20s");
    }

    @Test
    void drainAllByTraceId_groupsRecordsByTraceId() throws Exception {
        String topic = "test-topic";

        KafkaCaptureReceiver receiver = new KafkaCaptureReceiver(bootstrapServers);
        receiver.start();

        try {
            String traceId1 = "aaaaaaaabbbbbbbbccccccccdddddddd";
            String traceId2 = "11111111222222223333333344444444";
            String tp1 = "00-" + traceId1 + "-00f067aa0ba902b7-01";
            String tp2 = "00-" + traceId2 + "-00f067aa0ba902b8-01";

            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                // Two records for traceId1
                ProducerRecord<String, String> r1a = new ProducerRecord<>(topic, "k1a", "{\"v\":1}");
                r1a.headers().add("traceparent", tp1.getBytes(StandardCharsets.UTF_8));
                producer.send(r1a).get();

                ProducerRecord<String, String> r1b = new ProducerRecord<>(topic, "k1b", "{\"v\":2}");
                r1b.headers().add("traceparent", tp1.getBytes(StandardCharsets.UTF_8));
                producer.send(r1b).get();

                // One record for traceId2
                ProducerRecord<String, String> r2 = new ProducerRecord<>(topic, "k2", "{\"v\":3}");
                r2.headers().add("traceparent", tp2.getBytes(StandardCharsets.UTF_8));
                producer.send(r2).get();
            }

            // Allow background consumer to buffer the records before draining
            Thread.sleep(1000);

            java.util.Map<String, java.util.List<KafkaCaptureReceiver.CapturedRecord>> result =
                    receiver.drainAllByTraceId(50);

            assertThat(result).containsKey(traceId1);
            assertThat(result.get(traceId1)).hasSize(2);
            assertThat(result.get(traceId1).stream().map(KafkaCaptureReceiver.CapturedRecord::key).toList())
                    .containsExactlyInAnyOrder("k1a", "k1b");

            assertThat(result).containsKey(traceId2);
            assertThat(result.get(traceId2)).hasSize(1);
            assertThat(result.get(traceId2).get(0).key()).isEqualTo("k2");

            // Queue must be empty after drain
            java.util.Map<String, java.util.List<KafkaCaptureReceiver.CapturedRecord>> second =
                    receiver.drainAllByTraceId(0);
            assertThat(second).isEmpty();
        } finally {
            receiver.close();
        }
    }

    @Test
    void drainAllByTraceId_emptyWhenNoRecordsBuffered() throws Exception {
        KafkaCaptureReceiver receiver = new KafkaCaptureReceiver(bootstrapServers);
        receiver.start();
        try {
            java.util.Map<String, java.util.List<KafkaCaptureReceiver.CapturedRecord>> result =
                    receiver.drainAllByTraceId(0);
            assertThat(result).isEmpty();
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
