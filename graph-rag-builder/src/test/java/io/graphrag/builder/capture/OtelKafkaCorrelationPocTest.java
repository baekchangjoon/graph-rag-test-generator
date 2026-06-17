package io.graphrag.builder.capture;

import io.graphrag.builder.capture.otlp.SpanRecord;
import io.graphrag.builder.coverage.OtelAgent;
import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutOptions;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC② (plan Phase 5 게이트, spike — TDD 아님): Kafka consumer trace 상관 방식 실측.
 *
 * <p>order-service(@KafkaListener "order.events")를 OTEL agent(otlp export +
 * capture-query-parameters)로 띄우고, {@code traceparent} 헤더를 단 레코드를 발행한 뒤
 * consumer가 만든 DB span이 (a) 주입한 traceId와 같은 trace에 있는지(<b>child</b>),
 * (b) 다른 traceId지만 그 span/trace의 link에 주입 traceId가 있는지(<b>link</b>),
 * (c) 둘 다 아닌지(<b>uncorrelated</b>)를 판정한다. 이 결과가 Task 5.1의 awaitEntrySpan
 * 매칭 전략과 폴백을 확정한다(plan Phase 5).
 *
 * <p>판정 결과는 stdout으로 명확히 출력하고 plan "PoC 결과"에 옮긴다. Docker 필요.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class OtelKafkaCorrelationPocTest {

    private static final String TOPIC = "order.events";
    private static final long QUIESCE_TIMEOUT_MILLIS = 25_000;
    private static final long QUIESCENCE_MILLIS = 1_000;

    @TempDir
    Path out;

    @Test
    void measureKafkaConsumerTraceCorrelation() throws Exception {
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path externalStubs = Path.of(System.getProperty("external.stubs"));
        Path workDir = out.resolve("work");
        java.nio.file.Files.createDirectories(workDir);

        DbConfig dbConfig = new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app");

        // 결정적 TraceParent: 같은 runId로 만든 OtelSpanCapture가 동일 ids를 발급하므로,
        // 발행 시 주입한 traceparent를 나중에 실제 drain 경로로 그대로 환원할 수 있다.
        String runId = "poc2-kafka";
        TraceParent.Ids injected = new TraceParent(runId).next();
        String injectedTrace = injected.traceId();
        String injectedSpan = injected.spanId();

        try (AnalysisEnvironment env = new AnalysisEnvironment(dbConfig, false, true)) {
            OtelAgent otel = OtelAgent.prepare(workDir);
            SutOptions sutOptions = new SutOptions(
                    otel.javaToolOptions(), Map.of(), otel.env("order-service"), null);
            env.start(sutJar, workDir, sutOptions, externalStubs,
                    Map.of("EXTERNAL_INVENTORY_URL", AnalysisEnvironment.WIREMOCK_PLACEHOLDER),
                    otel, "order-service");

            // traceparent 헤더를 단 레코드 발행. payload는 OrderEventConsumer가 INSERT/SELECT를 내도록 유효.
            String payload = "{\"eventId\":\"poc-evt-1\",\"type\":\"CREATED\",\"userId\":\"poc-user-1\"}";
            try (KafkaProducer<String, String> producer =
                         new KafkaProducer<>(producerProps(env.kafkaBootstrapServers()))) {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(TOPIC, "poc-user-1", payload);
                record.headers().add("traceparent",
                        injected.header().getBytes(StandardCharsets.UTF_8));
                producer.send(record).get();
            }

            awaitQuiescence(env);

            Map<String, List<SpanRecord>> all = env.otlpReceiver().snapshot();
            String report = report(all, injectedTrace, injectedSpan);
            System.out.println(report);

            // DB span 판별: 신규(db.query.text) 또는 구(db.statement) semconv 모두 고려.
            List<SpanRecord> dbSpans = all.values().stream().flatMap(List::stream)
                    .filter(s -> s.attributes().containsKey("db.query.text")
                            || s.attributes().containsKey("db.statement")
                            || s.attributes().containsKey("db.system")
                            || s.attributes().containsKey("db.system.name"))
                    .toList();

            // DB span들의 전체 속성 덤프 — 어떤 키로 SQL/파라미터가 노출되는지 실측.
            System.out.println("===== PoC② DB span attributes =====");
            dbSpans.forEach(s -> System.out.println(
                    "[" + s.traceId() + "] " + s.name() + " " + s.attributes()));

            // sanity: agent가 DB span을 실제로 보냈는가 (캡처 자체가 동작?)
            assertThat(dbSpans)
                    .as("consumer DB span captured via OTEL? (없으면 agent/instrumentation 미동작)\n" + report)
                    .isNotEmpty();

            String model = classify(all, dbSpans, injectedTrace, injectedSpan);
            System.out.println("=== PoC② VERDICT: Kafka consumer correlation model = " + model + " ===");

            // 게이트②: child 또는 link 둘 중 하나여야 OTEL Kafka 귀속 GO. uncorrelated면 no-go(폴백).
            assertThat(model)
                    .as("Kafka consumer DB span이 주입 traceparent와 상관되는가?\n" + report)
                    .isIn("CHILD", "LINK");

            // 전체 파이프라인 검증: 실제 OtelSpanCapture.drain()이 이 trace를 app SQL로 환원하는가.
            // 같은 runId → begin()이 위와 동일 ids 발급 → 수신된 consumer span을 그대로 환원.
            OtelSpanCapture pipeline = new OtelSpanCapture(env.otlpReceiver(), noopSut(), new TraceParent(runId));
            List<ParsedSql> captured = pipeline.begin().drain();
            System.out.println("=== PoC② pipeline drain -> " + captured.size() + " ParsedSql ===");
            captured.forEach(p -> System.out.println("  " + p.sql() + "  binds="
                    + p.bindings().stream().map(ParsedSql.Binding::value).toList()));
            assertThat(captured)
                    .as("OtelSpanCapture가 consumer trace를 app SQL로 환원(폴백 아님)\n" + report)
                    .anyMatch(p -> p.sql().toLowerCase().contains("insert into order_events"));
            assertThat(captured)
                    .anyMatch(p -> p.bindings().stream()
                            .anyMatch(b -> "poc-user-1".equals(b.value())));
        }
    }

    /** child: db span 중 injectedTrace에 속한 것이 있음. link: 다른 trace지만 injectedTrace로의 link 보유. */
    private static String classify(Map<String, List<SpanRecord>> all, List<SpanRecord> dbSpans,
                                   String injectedTrace, String injectedSpan) {
        boolean child = dbSpans.stream().anyMatch(s -> injectedTrace.equals(s.traceId()));
        if (child) {
            return "CHILD";
        }
        // db span이 속한 trace의 어떤 span이든 injectedTrace를 link로 들고 있으면 link 모델.
        boolean link = dbSpans.stream().map(SpanRecord::traceId).distinct()
                .anyMatch(tid -> all.getOrDefault(tid, List.of()).stream()
                        .anyMatch(s -> s.linkedTraceIds().contains(injectedTrace)));
        if (link) {
            return "LINK";
        }
        // 약한 신호: entry/consumer span이 injectedSpan을 parent로 가지면 child 변형으로 본다.
        boolean parentMatch = all.values().stream().flatMap(List::stream)
                .anyMatch(s -> injectedSpan.equals(s.parentSpanId()));
        return parentMatch ? "CHILD" : "UNCORRELATED";
    }

    private static String report(Map<String, List<SpanRecord>> all, String injectedTrace, String injectedSpan) {
        StringBuilder sb = new StringBuilder("\n===== PoC② OTLP snapshot =====\n");
        sb.append("injected traceId=").append(injectedTrace)
                .append(" spanId=").append(injectedSpan).append('\n');
        all.forEach((tid, spans) -> {
            sb.append("trace ").append(tid)
                    .append(tid.equals(injectedTrace) ? "  <== INJECTED" : "").append('\n');
            for (SpanRecord s : spans) {
                sb.append("  - name='").append(s.name()).append("' kind=").append(s.kind())
                        .append(" span=").append(s.spanId())
                        .append(" parent=").append(s.parentSpanId());
                if (!s.linkedTraceIds().isEmpty()) {
                    sb.append(" links=").append(s.linkedTraceIds());
                }
                String sql = s.attributes().get("db.query.text");
                if (sql != null) {
                    sb.append(" db.query.text='").append(sql).append('\'');
                }
                sb.append('\n');
            }
        });
        return sb.toString();
    }

    /** 마지막 span 도착 후 QUIESCENCE_MILLIS 동안 신규 span이 없을 때까지(또는 timeout) 대기. */
    private static void awaitQuiescence(AnalysisEnvironment env) throws InterruptedException {
        long deadline = System.nanoTime() + QUIESCE_TIMEOUT_MILLIS * 1_000_000L;
        int lastCount = -1;
        long stableSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int count = env.otlpReceiver().snapshot().values().stream().mapToInt(List::size).sum();
            if (count != lastCount) {
                lastCount = count;
                stableSince = System.nanoTime();
            } else if (count > 0 && (System.nanoTime() - stableSince) >= QUIESCENCE_MILLIS * 1_000_000L) {
                return;
            }
            Thread.sleep(200);
        }
    }

    /** 폴백 비활성용 noop SutHandle — drain()이 OTEL 스팬만으로 환원함을 보장(로그 폴백 0). */
    private static io.graphrag.builder.env.SutHandle noopSut() {
        return new io.graphrag.builder.env.SutHandle() {
            public String baseUri() { return ""; }
            public String readLog() { return ""; }
            public long logOffset() { return 0; }
            public String readLogFrom(long o) { return ""; }
            public String readLogRange(long s, long e) { return ""; }
            public void stop() { }
        };
    }

    private static Properties producerProps(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }
}
