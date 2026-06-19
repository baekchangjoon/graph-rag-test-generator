package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.graphrag.model.Json;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class KafkaCaptureReceiver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaCaptureReceiver.class);
    private static final int MAX_QUEUE_SIZE = 10000;
    private static final Pattern TOPIC_REGEX = Pattern.compile("^(?!_).+");

    public record CapturedRecord(
            String topic,
            String key,
            JsonNode value,
            Map<String, String> headers
    ) {}

    private final String bootstrapServers;
    private final Queue<CapturedRecord> queue = new ConcurrentLinkedQueue<>();
    private int queueSize = 0;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private KafkaConsumer<String, String> consumer;
    private Thread pollThread;

    public KafkaCaptureReceiver(String bootstrapServers) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers);
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "graph-rag-capture-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        consumer = new KafkaConsumer<>(props);

        List<String> topicsToSubscribe = new ArrayList<>();
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (AdminClient admin = AdminClient.create(adminProps)) {
            Set<String> allTopics = admin.listTopics().names().get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (String t : allTopics) {
                if (TOPIC_REGEX.matcher(t).matches()) {
                    topicsToSubscribe.add(t);
                }
            }
            log.info("KafkaCaptureReceiver matched topics via AdminClient: {}", topicsToSubscribe);
        } catch (Exception e) {
            log.warn("Failed to list topics via AdminClient. Will subscribe using Pattern.", e);
        }

        if (!topicsToSubscribe.isEmpty()) {
            consumer.subscribe(topicsToSubscribe);
        } else {
            consumer.subscribe(TOPIC_REGEX);
        }

        pollThread = new Thread(this::pollLoop, "kafka-capture-receiver-poll");
        pollThread.setDaemon(true);
        pollThread.start();
        log.info("KafkaCaptureReceiver started polling on {}", bootstrapServers);
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : records) {
                    processRecord(record);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("Error in Kafka polling thread", e);
            }
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        Map<String, String> headersMap = new LinkedHashMap<>();
        for (Header h : record.headers()) {
            if (h.value() != null) {
                headersMap.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
            }
        }

        JsonNode valueNode;
        String rawValue = record.value();
        if (rawValue == null) {
            valueNode = NullNode.getInstance();
        } else {
            try {
                valueNode = Json.mapper().readTree(rawValue);
            } catch (IOException e) {
                valueNode = new TextNode(rawValue);
            }
        }

        CapturedRecord captured = new CapturedRecord(
                record.topic(),
                record.key(),
                valueNode,
                headersMap
        );

        addRecord(captured);
    }

    private void addRecord(CapturedRecord record) {
        synchronized (queue) {
            while (queueSize >= MAX_QUEUE_SIZE) {
                CapturedRecord removed = queue.poll();
                if (removed != null) {
                    queueSize--;
                } else {
                    break;
                }
            }
            if (queue.offer(record)) {
                queueSize++;
                queue.notifyAll();
            }
        }
    }

    /**
     * 첫 매칭 레코드를 기다리는 짧은 윈도우. 대부분의 탐색 요청은 Kafka emit이 없는데, 이게 없으면
     * drain이 매번 full deadline(예: 5s)을 block한다(요청당 5s 낭비 → 풀빌드에서 수 분 누적; OTEL
     * 모드만 per-request drain 호출이라 OTEL 풀빌드가 none보다 ~8배 느렸던 근본원인). 첫 매칭이 이
     * 윈도우 내에 없으면 빈 결과로 즉시 반환한다. 매칭이 시작되면 settle/deadline 로직으로 전환.
     * 로컬 Kafka produce→consume 지연(<수백 ms)에 안전한 여유값.
     */
    static final long FIRST_MATCH_TIMEOUT_NANOS = 200_000_000L; // 200ms (quick fix)

    public List<CapturedRecord> drain(String traceId, long timeoutMillis) {
        long startNanos = System.nanoTime();
        long deadline = startNanos + timeoutMillis * 1_000_000L;
        long firstMatchDeadline = Math.min(deadline, startNanos + FIRST_MATCH_TIMEOUT_NANOS);
        List<CapturedRecord> matched = new ArrayList<>();
        long settleTimeoutNanos = 100_000_000L; // 100ms settle timeout
        long lastMatchedTime = 0;

        while (true) {
            synchronized (queue) {
                boolean foundNew = false;
                Iterator<CapturedRecord> it = queue.iterator();
                while (it.hasNext()) {
                    CapturedRecord rec = it.next();
                    String recordTraceId = getTraceIdFromHeaders(rec.headers());
                    if (Objects.equals(traceId, recordTraceId)) {
                        matched.add(rec);
                        it.remove();
                        queueSize--;
                        foundNew = true;
                    }
                }

                long now = System.nanoTime();
                if (foundNew) {
                    lastMatchedTime = now;
                }

                if (now >= deadline) {
                    break;
                }

                if (matched.isEmpty()) {
                    // 첫 매칭 전: first-match 윈도우까지만 대기(없으면 빈 결과 반환).
                    if (now >= firstMatchDeadline) {
                        break;
                    }
                } else if (now - lastMatchedTime >= settleTimeoutNanos) {
                    break;
                }

                // 대기 상한: 첫 매칭 전이면 first-match deadline, 이후엔 overall deadline.
                long waitNanos = (matched.isEmpty() ? firstMatchDeadline : deadline) - now;

                if (!matched.isEmpty()) {
                    long remainingToSettleNanos = settleTimeoutNanos - (now - lastMatchedTime);
                    if (remainingToSettleNanos < waitNanos) {
                        waitNanos = remainingToSettleNanos;
                    }
                }

                long waitMillis = waitNanos / 1_000_000L;
                long waitNanosRemaining = waitNanos % 1_000_000L;

                if (waitMillis <= 0 && waitNanosRemaining <= 0) {
                    break;
                }

                try {
                    queue.wait(waitMillis, (int) waitNanosRemaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return matched;
    }

    private String getTraceIdFromHeaders(Map<String, String> headers) {
        String tp = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("traceparent".equalsIgnoreCase(entry.getKey())) {
                tp = entry.getValue();
                break;
            }
        }
        if (tp == null) {
            return null;
        }
        String[] parts = tp.split("-");
        if (parts.length >= 2) {
            String candidate = parts[1];
            if (candidate.length() == 32) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * End-of-exploration drain: brief settle for stragglers, then remove & return ALL buffered
     * records grouped by their traceparent traceId. Non-per-request → no per-request blocking.
     */
    public java.util.Map<String, java.util.List<CapturedRecord>> drainAllByTraceId(long settleMillis) {
        try { Thread.sleep(settleMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        java.util.Map<String, java.util.List<CapturedRecord>> byTrace = new java.util.LinkedHashMap<>();
        synchronized (queue) {
            for (CapturedRecord rec : queue) {
                String tid = getTraceIdFromHeaders(rec.headers());
                if (tid != null) byTrace.computeIfAbsent(tid, k -> new java.util.ArrayList<>()).add(rec);
            }
            queue.clear();
            queueSize = 0;
        }
        return byTrace;
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        
        if (consumer != null) {
            try {
                consumer.wakeup();
            } catch (Exception ignored) {}
        }

        if (pollThread != null) {
            try {
                pollThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception ignored) {}
        }
        log.info("KafkaCaptureReceiver closed");
    }
}
