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

        // Wait briefly for consumer group subscription/assignment to be ready
        // (with auto.offset.reset = latest, we want consumer to be ready before we produce)
        Thread.sleep(1000);

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
}
