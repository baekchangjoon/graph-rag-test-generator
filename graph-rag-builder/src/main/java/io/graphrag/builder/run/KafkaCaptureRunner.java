package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlLogParser;
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
 * 타임아웃이면 진행한다(SQL-less consumer(Redis)는 전역 커버리지에 반영됨).
 */
public class KafkaCaptureRunner {

    private static final Logger log = LoggerFactory.getLogger(KafkaCaptureRunner.class);
    private static final long AWAIT_MILLIS = 8000;
    private static final long POLL_MILLIS = 250;

    public record KafkaResult(List<KafkaExchange> exchanges, List<CapturedSql> sql) {
    }

    private final SutProcess sut;
    private final Connection connection;
    private final DbConfig.Type dbType;
    private final String bootstrapServers;

    public KafkaCaptureRunner(SutProcess sut, Connection connection, DbConfig.Type dbType,
                              String bootstrapServers) {
        this.sut = sut;
        this.connection = connection;
        this.dbType = dbType;
        this.bootstrapServers = bootstrapServers;
    }

    public KafkaResult run(KafkaConsumer consumer, BodyShape shape, List<TableSchema> tables)
            throws Exception {
        if (consumer.topic() == null || consumer.topic().contains("${")) {
            log.warn("kafka consumer {} skipped: unresolved topic {}", consumer.id(), consumer.topic());
            return new KafkaResult(List.of(), List.of());
        }
        SynthesizedInput happy = shape == null
                ? new SynthesizedInput(Json.mapper().createObjectNode(), List.of())
                : new SampleInputSynthesizer().synthesize(shape, tables);
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, dbType, seed);
        }

        List<KafkaExchange> exchanges = new ArrayList<>();
        List<CapturedSql> allSql = new ArrayList<>();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            int sequence = 0;
            ObjectNode payload = happy.body();
            sequence++;
            String exchangeId = consumer.id() + "-x" + sequence;
            long logStart = sut.logOffset();

            String key = payload.has("userId") ? payload.get("userId").asText() : exchangeId;
            producer.send(new ProducerRecord<>(consumer.topic(), key,
                    Json.mapper().writeValueAsString(payload))).get();

            awaitConsumerSql(logStart);
            long logEnd = sut.logOffset();
            List<CapturedSql> sql = captureSql(exchangeId, payload, sut.readLogRange(logStart, logEnd));
            allSql.addAll(sql);
            exchanges.add(new KafkaExchange(exchangeId, consumer.id(), consumer.topic(), payload,
                    sql.stream().map(CapturedSql::id).toList()));
            log.info("kafka published {} -> topic {} ({} sql)", exchangeId, consumer.topic(), sql.size());
        }
        return new KafkaResult(exchanges, allSql);
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
