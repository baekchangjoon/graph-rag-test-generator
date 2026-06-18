package io.graphrag.testlib.api;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/** 생성 Kafka 테스트용 최소 producer 래퍼 (캡처 경로 = 테스트 경로). */
public final class KafkaHelper implements AutoCloseable {

    private final String bootstrapServers;
    private final KafkaProducer<String, String> producer;
    private final java.util.List<ConsumerRunner> consumers = new java.util.ArrayList<>();
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.BlockingQueue<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>> buffers = new java.util.concurrent.ConcurrentHashMap<>();

    public KafkaHelper(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
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

    /** Subscribes to a given topic using a KafkaConsumer running on a background thread. */
    public synchronized void subscribe(String topic) {
        if (buffers.containsKey(topic)) {
            return;
        }
        buffers.computeIfAbsent(topic, k -> new java.util.concurrent.LinkedBlockingQueue<>());
        ConsumerRunner runner = new ConsumerRunner(bootstrapServers, topic, buffers.get(topic));
        consumers.add(runner);
        runner.start();
    }

    /** Waits for the next record on the specified topic and returns it. If it times out, returns null. */
    public org.apache.kafka.clients.consumer.ConsumerRecord<String, String> consumeNextRecord(String topic, java.time.Duration timeout) {
        java.util.concurrent.BlockingQueue<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> queue = buffers.get(topic);
        if (queue == null) {
            throw new IllegalStateException("Not subscribed to topic: " + topic);
        }
        try {
            return queue.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consumer interrupted", e);
        }
    }

    @Override
    public synchronized void close() {
        for (ConsumerRunner runner : consumers) {
            runner.close();
        }
        consumers.clear();
        producer.close();
    }

    public synchronized boolean isAssigned(String topic) {
        for (ConsumerRunner runner : consumers) {
            if (runner.topic.equals(topic) && runner.isAssigned()) {
                return true;
            }
        }
        return false;
    }

    private static final class ConsumerRunner implements Runnable, AutoCloseable {
        private final String topic;
        private final java.util.concurrent.BlockingQueue<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> queue;
        private final org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer;
        private final Thread thread;
        private volatile boolean closed = false;
        private volatile boolean assigned = false;

        public ConsumerRunner(String bootstrapServers, String topic, java.util.concurrent.BlockingQueue<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> queue) {
            this.topic = topic;
            this.queue = queue;

            Properties props = new Properties();
            props.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + java.util.UUID.randomUUID().toString());
            props.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            props.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class.getName());
            props.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class.getName());
            props.put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

            this.consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
            this.thread = new Thread(this, "kafka-helper-consumer-" + topic + "-" + props.getProperty(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG));
        }

        public void start() {
            this.thread.start();
        }

        public boolean isAssigned() {
            return assigned;
        }

        @Override
        public void run() {
            try {
                consumer.subscribe(java.util.Collections.singletonList(topic));
                while (!closed) {
                    try {
                        org.apache.kafka.clients.consumer.ConsumerRecords<String, String> records =
                                consumer.poll(java.time.Duration.ofMillis(100));
                        this.assigned = !consumer.assignment().isEmpty();
                        for (org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record : records) {
                            queue.put(record);
                        }
                    } catch (org.apache.kafka.common.errors.WakeupException e) {
                        if (!closed) {
                            throw e;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        if (closed) {
                            break;
                        }
                        e.printStackTrace();
                    }
                }
            } finally {
                try {
                    consumer.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            consumer.wakeup();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
