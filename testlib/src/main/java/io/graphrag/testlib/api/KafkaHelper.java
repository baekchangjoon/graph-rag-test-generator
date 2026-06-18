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

    /**
     * Subscribes to a given topic using a KafkaConsumer running on a background thread.
     * 반환 전 파티션 할당 완료까지 대기한다 — consumer는 auto.offset.reset=latest이므로,
     * 할당 전에 SUT가 발행하면 레코드가 유실되어 단언이 간헐 실패(flaky)한다. 할당을 보장하면
     * subscribe 직후 API를 호출하는 생성 테스트가 발행 이벤트를 놓치지 않는다.
     */
    public synchronized void subscribe(String topic) {
        if (buffers.containsKey(topic)) {
            return;
        }
        buffers.computeIfAbsent(topic, k -> new java.util.concurrent.LinkedBlockingQueue<>());
        ConsumerRunner runner = new ConsumerRunner(bootstrapServers, topic, buffers.get(topic));
        consumers.add(runner);
        runner.start();
        long deadlineNanos = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (!runner.isAssigned() && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Waits for the next record on the specified topic and returns it. If it times out, returns null. */
    public org.apache.kafka.clients.consumer.ConsumerRecord<String, String> consumeNextRecord(String topic, java.time.Duration timeout) {
        return consumeNextRecord(topic, null, timeout);
    }

    /**
     * 다음 레코드를 반환하되, expectedKey가 non-null이면 그 key와 일치하는 레코드만 반환한다.
     * 같은 토픽을 공유하는 다른 테스트가 넣은 레코드(다른 key)는 건너뛴다(토픽 오염 격리).
     * 타임아웃 내에 일치 레코드가 없으면 null.
     */
    public org.apache.kafka.clients.consumer.ConsumerRecord<String, String> consumeNextRecord(String topic, String expectedKey, java.time.Duration timeout) {
        java.util.concurrent.BlockingQueue<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> queue = buffers.get(topic);
        if (queue == null) {
            throw new IllegalStateException("Not subscribed to topic: " + topic);
        }
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record =
                        queue.poll(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                if (record == null || expectedKey == null || expectedKey.equals(record.key())) {
                    return record;
                }
                // expectedKey 불일치 → 다른 테스트의 오염 레코드, 건너뛴다.
            }
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
