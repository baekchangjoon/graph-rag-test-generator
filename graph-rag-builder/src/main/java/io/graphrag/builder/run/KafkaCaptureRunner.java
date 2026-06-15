package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlLogParser;
import io.graphrag.builder.coverage.CoverageClient;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutProcess;
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

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private final SutProcess sut;
    private final Connection connection;
    private final DbConfig.Type dbType;
    private final String bootstrapServers;
    private final CoverageClient coverage;

    public KafkaCaptureRunner(SutProcess sut, Connection connection, DbConfig.Type dbType,
                              String bootstrapServers, CoverageClient coverage) {
        this.sut = sut;
        this.connection = connection;
        this.dbType = dbType;
        this.bootstrapServers = bootstrapServers;
        this.coverage = coverage;
    }

    public KafkaResult run(KafkaConsumer consumer, BodyShape shape, List<TableSchema> tables)
            throws Exception {
        if (consumer.topic() == null || consumer.topic().contains("${")) {
            log.warn("kafka consumer {} skipped: unresolved topic {}", consumer.id(), consumer.topic());
            return new KafkaResult(List.of(), List.of(), new ExecutionDataStore());
        }
        SynthesizedInput happy = shape == null
                ? new SynthesizedInput(Json.mapper().createObjectNode(), List.of())
                : new SampleInputSynthesizer().synthesize(shape, tables);
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, dbType, seed);
        }

        List<KafkaExchange> exchanges = new ArrayList<>();
        List<CapturedSql> allSql = new ArrayList<>();
        ExecutionDataStore cumulativeExec = new ExecutionDataStore();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            ObjectNode payload = happy.body();
            String exchangeId = consumer.id() + "-x1";
            String key = payload.has("userId") ? payload.get("userId").asText() : exchangeId;
            // happy: SQL 출현까지 폴링 (consumer가 만든 행 캡처)
            List<CapturedSql> happySql = publishAndCapture(producer, consumer, exchangeId, payload, key,
                    false, true, exchanges, allSql, cumulativeExec);

            // 반대-arm 변종(결측-필드 / 중복) — GRB_KAFKA_VARIANTS=off면 skip.
            if (!"off".equalsIgnoreCase(System.getenv("GRB_KAFKA_VARIANTS"))) {
                // missing-field(결정적, 하드): 빈 payload → required-필드 null-guard early-return arm.
                publishAndCapture(producer, consumer, consumer.id() + "-missing",
                        Json.mapper().createObjectNode(), variantKey(consumer, "missing"),
                        true, false, exchanges, allSql, cumulativeExec);
                // duplicate(best-effort): happy 행 커밋 가시성 확인 후 동일 payload 재발행 → dedup-skip arm.
                if (awaitHappyRowCommitted(happySql, tables)) {
                    publishAndCapture(producer, consumer, consumer.id() + "-dup",
                            payload.deepCopy(), key, true, false, exchanges, allSql, cumulativeExec);
                }
            }
        }
        return new KafkaResult(exchanges, allSql, cumulativeExec);
    }

    /** 1회 발행 + (SQL await | 고정 settle) + 커버리지 delta + SQL/교환 캡처. */
    private List<CapturedSql> publishAndCapture(KafkaProducer<String, String> producer,
            KafkaConsumer consumer, String exchangeId, ObjectNode payload, String key, boolean variant,
            boolean awaitSql, List<KafkaExchange> exchanges, List<CapturedSql> allSql,
            ExecutionDataStore cumulativeExec) throws Exception {
        coverage.dump(true);   // baseline: 직전 구간 컷, 이 발행의 delta만 측정
        long logStart = sut.logOffset();
        producer.send(new ProducerRecord<>(consumer.topic(), key,
                Json.mapper().writeValueAsString(payload))).get();
        if (awaitSql) {
            awaitConsumerSql(logStart);
        } else {
            Thread.sleep(VARIANT_SETTLE_MILLIS);   // SQL 없는 변종: 고정 settle
        }
        long logEnd = sut.logOffset();
        coverage.dump(true).accept(cumulativeExec);   // consumer 실행 커버 delta
        List<CapturedSql> sql = captureSql(exchangeId, payload, sut.readLogRange(logStart, logEnd));
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

    /** consumer가 만든 SQL이 로그에 나타날 때까지 폴링(최대 AWAIT). 없으면(Redis 등) 타임아웃 후 진행. */
    private void awaitConsumerSql(long logStart) throws Exception {
        long deadline = System.nanoTime() + AWAIT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS);
            if (!SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset())).isEmpty()) {
                Thread.sleep(POLL_MILLIS);   // settle: 후속 SQL flush 여유
                return;
            }
        }
    }

    private Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    private List<CapturedSql> captureSql(String exchangeId, JsonNode payload, String logSegment) {
        Set<String> payloadValues = new HashSet<>();
        payload.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) {
                payloadValues.add(entry.getValue().asText());
            }
        });
        List<CapturedSql> captured = new ArrayList<>();
        int sequence = 0;
        for (ParsedSql statement : SqlLogParser.parse(logSegment)) {
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
