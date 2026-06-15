package io.graphrag.testlib.api;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/** 생성 Kafka 테스트용 최소 producer 래퍼 (캡처 경로 = 테스트 경로). */
public final class KafkaHelper implements AutoCloseable {

    private final KafkaProducer<String, String> producer;

    public KafkaHelper(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        this.producer = new KafkaProducer<>(props);
    }

    /** 토픽에 JSON 이벤트를 발행하고 broker ack까지 블록. */
    public void send(String topic, String key, String jsonValue) {
        try {
            producer.send(new ProducerRecord<>(topic, key, jsonValue)).get();
        } catch (Exception e) {
            throw new IllegalStateException("kafka send failed: " + topic, e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
