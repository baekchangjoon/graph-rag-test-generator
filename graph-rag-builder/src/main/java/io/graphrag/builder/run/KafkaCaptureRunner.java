package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlCaptureBackend;
import io.graphrag.builder.coverage.CoverageProbe;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Json;
import io.graphrag.model.KafkaConsumer;
import io.graphrag.model.KafkaExchange;
import io.graphrag.model.SqlBinding;
import io.graphrag.model.TableSchema;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @KafkaListener consumer의 결정적 발행 캡처 (WS 패턴 차용). consumer는 @SendTo reply가 없으므로
 * WS의 request/reply await와 비동형 — 발행 후 consumer가 만든 SQL이 로그에 나타날 때까지 폴링하고,
 * 타임아웃이면 진행한다(SQL-less consumer(Redis)도 커버리지 delta로 잡힌다).
 *
 * <p>발행 직전 baseline dump(reset)로 boot/seed 구간을 잘라내고, await 후 dump delta로 consumer
 * 실행 커버리지를 떠서 {@link KafkaResult#cumulativeExec()}로 반환한다. BuilderCli가 이를 runWideExec에
 * 병합해 exploration 지표에 consumer 커버가 포함되게 한다(SQL 없는 Redis consumer도 동일).
 */
public class KafkaCaptureRunner {

    private static final Logger log = LoggerFactory.getLogger(KafkaCaptureRunner.class);
    private static final long AWAIT_MILLIS = 8000;
    private static final long POLL_MILLIS = 250;
    // SQL을 안 만드는 변종(스킵/리턴 arm)은 완료 신호가 없어 고정 settle 후 dump (POLL_MILLIS×10).
    private static final long VARIANT_SETTLE_MILLIS = 2500;

    public record KafkaResult(List<KafkaExchange> exchanges, List<CapturedSql> sql,
                              ExecutionDataStore cumulativeExec) {
    }

    private final Connection connection;
    private final DbConfig.Type dbType;
    private final String bootstrapServers;
    private final CoverageProbe coverage;
    private final SqlCaptureBackend sqlCapture;
    // pjacoco per-trace: 전체 빌드 런 공유 생성기 — runner 간 traceId 충돌 없음 (Phase 2 동시 실행 안전).
    private final io.graphrag.builder.capture.TraceParent traceParent;

    public KafkaCaptureRunner(Connection connection, DbConfig.Type dbType,
                              String bootstrapServers, CoverageProbe coverage,
                              SqlCaptureBackend sqlCapture) {
        this(connection, dbType, bootstrapServers, coverage, sqlCapture, null);
    }

    public KafkaCaptureRunner(Connection connection, DbConfig.Type dbType,
                              String bootstrapServers, CoverageProbe coverage,
                              SqlCaptureBackend sqlCapture,
                              io.graphrag.builder.capture.TraceParent traceParent) {
        this.connection = connection;
        this.dbType = dbType;
        this.bootstrapServers = bootstrapServers;
        this.coverage = coverage;
        this.sqlCapture = sqlCapture;
        this.traceParent = traceParent != null ? traceParent
                : new io.graphrag.builder.capture.TraceParent("kafka-" + System.nanoTime());
    }

    public KafkaResult run(KafkaConsumer consumer, BodyShape shape, List<TableSchema> tables)
            throws Exception {
        if (consumer.topic() == null || consumer.topic().contains("${")) {
            log.warn("kafka consumer {} skipped: unresolved topic {}", consumer.id(), consumer.topic());
            return new KafkaResult(List.of(), List.of(), new ExecutionDataStore());
        }
        SynthesizedInput happy = shape == null
                ? new SynthesizedInput(Json.mapper().createObjectNode(), List.of())
                : new SampleInputSynthesizer(Map.of(), consumer.id()).synthesize(shape, tables);
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, dbType, seed);
        }

        List<KafkaExchange> exchanges = new ArrayList<>();
        List<CapturedSql> allSql = new ArrayList<>();
        ExecutionDataStore cumulativeExec = new ExecutionDataStore();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            JsonNode payload = happy.body();
            String exchangeId = consumer.id() + "-x1";
            String key = payload.isObject() && payload.has("userId")
                    ? payload.get("userId").asText() : exchangeId;
            // happy: SQL 출현까지 폴링 (consumer가 만든 행 캡처)
            List<CapturedSql> happySql = publishAndCapture(producer, consumer, exchangeId, payload, key,
                    false, true, exchanges, allSql, cumulativeExec);

            // 반대-arm 변종(결측-필드 / 중복) — GRB_KAFKA_VARIANTS=off면 skip. 컬렉션(array) payload는
            // happy-only(변종은 ObjectNode 전제) → object일 때만 변종 발행.
            if (payload instanceof ObjectNode objPayload
                    && !"off".equalsIgnoreCase(System.getenv("GRB_KAFKA_VARIANTS"))) {
                // missing-field(결정적, 하드): 빈 payload → required-필드 null-guard early-return arm.
                publishAndCapture(producer, consumer, consumer.id() + "-missing",
                        missingFieldPayload(), variantKey(consumer, "missing"),
                        true, false, exchanges, allSql, cumulativeExec);
                // duplicate(best-effort): happy 행 커밋 가시성 확인 후 동일 payload 재발행 → dedup-skip arm.
                if (awaitHappyRowCommitted(happySql, tables)) {
                    publishAndCapture(producer, consumer, consumer.id() + "-dup",
                            objPayload.deepCopy(), key, true, false, exchanges, allSql, cumulativeExec);
                }
            }
        }
        return new KafkaResult(exchanges, allSql, cumulativeExec);
    }

    /** 1회 발행 + (SQL await | 고정 settle) + 커버리지 delta + SQL/교환 캡처. */
    private List<CapturedSql> publishAndCapture(KafkaProducer<String, String> producer,
            KafkaConsumer consumer, String exchangeId, JsonNode payload, String key, boolean variant,
            boolean awaitSql, List<KafkaExchange> exchanges, List<CapturedSql> allSql,
            ExecutionDataStore cumulativeExec) throws Exception {
        coverage.baselineCut();   // baseline: 직전 구간 컷 (pjacoco: no-op — traceId별 스토어가 비어 시작)
        SqlCaptureBackend.Scope scope = sqlCapture.begin();
        ProducerRecord<String, String> record = new ProducerRecord<>(consumer.topic(), key,
                Json.mapper().writeValueAsString(payload));
        // 상관 헤더(OTEL: traceparent / log: 없음)를 레코드 헤더로 주입. add는 누적이라 remove 후 add.
        Map<String, String> scopeHeaders = scope.requestHeaders();
        scopeHeaders.forEach((k, v) -> {
            record.headers().remove(k);
            record.headers().add(k, v.getBytes(StandardCharsets.UTF_8));
        });
        // pjacoco: scope가 traceparent를 제공하지 않으면 생성한 traceId를 레코드 헤더로 주입.
        String probeTraceId = traceParent.next().traceId();
        String probeTraceparent = coverage.traceparentFor(probeTraceId);   // null = traceparent 미주입 probe(WS·테스트 폴백)
        if (probeTraceparent != null) {
            boolean scopeHasTraceparent = scopeHeaders.keySet().stream()
                    .anyMatch(k -> k.equalsIgnoreCase("traceparent"));
            if (!scopeHasTraceparent) {
                record.headers().remove("traceparent");
                record.headers().add("traceparent", probeTraceparent.getBytes(StandardCharsets.UTF_8));
            }
        }
        producer.send(record).get();
        // happy: SQL/entry span 출현까지 await. variant: 단축 settle(early-return arm은 빈 SQL 기대).
        List<ParsedSql> parsed = awaitSql ? scope.drain(AWAIT_MILLIS) : scope.drain(VARIANT_SETTLE_MILLIS);
        // traceId 결정: scope(OTEL) 제공 traceparent 우선, 없으면 probe traceId 사용.
        String traceId = extractTraceId(scopeHeaders);
        if (traceId == null) {
            traceId = probeTraceId;
        }
        coverage.requestDelta(traceId).accept(cumulativeExec);   // consumer 실행 커버 delta
        List<CapturedSql> sql = captureSql(exchangeId, payload, parsed);
        allSql.addAll(sql);
        exchanges.add(new KafkaExchange(exchangeId, consumer.id(), consumer.topic(), payload,
                sql.stream().map(CapturedSql::id).toList(), variant));
        log.info("kafka published {} -> topic {} ({} sql, variant={})",
                exchangeId, consumer.topic(), sql.size(), variant);
        return sql;
    }

    /** 결측 변종 합성: 전 필드 null인 빈 payload (역직렬화 시 required-필드 null-guard arm). */
    static ObjectNode missingFieldPayload() {
        return Json.mapper().createObjectNode();
    }

    /** 변종별 비충돌 합성 key. */
    static String variantKey(KafkaConsumer consumer, String kind) {
        return "variant-" + kind + "-" + consumer.id();
    }

    /**
     * happy consumer가 쓴 행의 커밋 가시성을 빌더 connection으로 확인(중복 변종이 dedup arm을 타도록).
     * happy가 INSERT를 캡처했고 그 테이블 PK 값으로 폴링 가능할 때만 true. INSERT 미캡처(Redis 등)면 false
     * → 중복 변종 best-effort 생략.
     */
    private boolean awaitHappyRowCommitted(List<CapturedSql> happySql, List<TableSchema> tables) {
        CapturedSql insert = happySql.stream().filter(s -> "INSERT".equals(s.sqlKind())).findFirst().orElse(null);
        if (insert == null) {
            return false;
        }
        String pk = tables.stream().filter(t -> t.name().equals(insert.tableName()))
                .flatMap(t -> t.columns().stream())
                .filter(io.graphrag.model.ColumnSchema::primaryKey)
                .map(io.graphrag.model.ColumnSchema::name).findFirst().orElse(null);
        SqlBinding pkBind = pk == null ? null
                : insert.bindings().stream().filter(b -> pk.equals(b.column())).findFirst().orElse(null);
        if (pkBind == null) {
            return false;
        }
        String sql = "SELECT count(*) FROM " + insert.tableName() + " WHERE " + pk + " = ?";
        long deadline = System.nanoTime() + AWAIT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, pkBind.value());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(1) >= 1) {
                        return true;
                    }
                }
            } catch (Exception e) {
                return false;   // 폴링 불가(타입 불일치 등) → best-effort 생략
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    /** scope requestHeaders에서 traceparent의 traceId(32-hex) 부분을 추출한다. 없으면 null. */
    private static String extractTraceId(Map<String, String> headers) {
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase("traceparent")) {
                String tp = h.getValue();
                if (tp != null) {
                    String[] parts = tp.split("-");
                    if (parts.length >= 2 && parts[1].length() == 32) {
                        return parts[1];
                    }
                }
            }
        }
        return null;
    }

    private List<CapturedSql> captureSql(String exchangeId, JsonNode payload, List<ParsedSql> parsed) {
        Set<String> payloadValues = EndpointExplorationRunner.collectBodyValues(payload);
        List<CapturedSql> captured = new ArrayList<>();
        int sequence = 0;
        for (ParsedSql statement : parsed) {
            sequence++;
            List<SqlBinding> bindings = new ArrayList<>();
            for (ParsedSql.Binding binding : statement.bindings()) {
                bindings.add(new SqlBinding(
                        binding.position(),
                        statement.columnForPosition(binding.position()),
                        binding.value(),
                        payloadValues.contains(binding.value())
                                ? BindingOrigin.API_PARAM : BindingOrigin.LITERAL,
                        statement.bindingTableForPosition(binding.position())));
            }
            captured.add(new CapturedSql("sql-" + exchangeId + "-" + sequence, exchangeId,
                    statement.kind(), statement.sql(), statement.tableName(), bindings));
        }
        return captured;
    }
}
