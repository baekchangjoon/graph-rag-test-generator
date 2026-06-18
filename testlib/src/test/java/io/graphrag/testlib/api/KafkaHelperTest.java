package io.graphrag.testlib.api;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaHelperTest {

    private static KafkaContainer kafka;
    private KafkaHelper kafkaHelper;

    @BeforeAll
    static void setUpAll() {
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));
        kafka.start();
    }

    @AfterAll
    static void tearDownAll() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @BeforeEach
    void setUp() {
        kafkaHelper = new KafkaHelper(kafka.getBootstrapServers());
    }

    @AfterEach
    void tearDown() {
        if (kafkaHelper != null) {
            kafkaHelper.close();
        }
    }

    @Test
    void testSubscribeAndConsumeNextRecord() throws Exception {
        String topic = "test-topic-" + java.util.UUID.randomUUID();

        // 1. Subscribe to the topic asynchronously
        kafkaHelper.subscribe(topic);
        kafkaHelper.subscribe(topic); // Double-subscribe to verify duplicate protection

        // Wait briefly for consumer group subscription/assignment to be ready
        // (with auto.offset.reset = latest, we want consumer to be ready before we produce)
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> kafkaHelper.isAssigned(topic));

        // 2. Publish a message to the topic
        String key = "key-1";
        String value = "{\"id\":1,\"name\":\"test\"}";
        kafkaHelper.send(topic, key, value);

        // 3. Consume the record
        ConsumerRecord<String, String> record = kafkaHelper.consumeNextRecord(topic, Duration.ofSeconds(5));
        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo(key);
        assertThat(record.value()).isEqualTo(value);

        // 4. Verify JSONAssert is available and works
        JSONAssert.assertEquals("{\"name\":\"test\",\"id\":1}", record.value(), true);
    }

    @Test
    void consumeNextRecord_byKey_skipsRecordsWithOtherKeys() throws Exception {
        // 공유 토픽 오염 방지: 다른 테스트가 같은 토픽에 넣은 레코드(다른 key)는 건너뛰고
        // 내 key의 레코드만 반환해야 한다.
        String topic = "test-topic-" + java.util.UUID.randomUUID();
        kafkaHelper.subscribe(topic);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> kafkaHelper.isAssigned(topic));

        kafkaHelper.send(topic, "other-user", "{\"eventId\":\"sample-eventId\",\"type\":\"sample-type\"}");
        kafkaHelper.send(topic, "my-user", "{\"eventId\":\"99\",\"type\":\"CREATED\"}");

        ConsumerRecord<String, String> record =
                kafkaHelper.consumeNextRecord(topic, "my-user", Duration.ofSeconds(5));
        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo("my-user");
        JSONAssert.assertEquals("{\"type\":\"CREATED\"}", record.value(), false);
    }
}
