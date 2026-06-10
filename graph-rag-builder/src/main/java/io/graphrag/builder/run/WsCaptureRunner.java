package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlLogParser;
import io.graphrag.builder.env.SutProcess;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Json;
import io.graphrag.model.SqlBinding;
import io.graphrag.model.TableSchema;
import io.graphrag.model.WsEndpoint;
import io.graphrag.model.WsExchange;
import io.graphrag.testlib.api.StompHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * STOMP endpoint의 결정적 메시지 교환 캡처 (roadmap 3.2).
 * happy + missing-ref 변형의 2종 payload. WS 분기 탐색은 보류
 * (docs/decisions/stomp-capture.md).
 */
public class WsCaptureRunner {

    private static final Logger log = LoggerFactory.getLogger(WsCaptureRunner.class);
    private static final Duration AWAIT = Duration.ofSeconds(8);

    public record WsResult(List<WsExchange> exchanges, List<CapturedSql> sql) {
    }

    private final SutProcess sut;
    private final Connection connection;

    public WsCaptureRunner(SutProcess sut, Connection connection) {
        this.sut = sut;
        this.connection = connection;
    }

    public WsResult run(WsEndpoint endpoint, BodyShape shape, List<TableSchema> tables)
            throws Exception {
        SynthesizedInput happy = new SampleInputSynthesizer().synthesize(shape, tables);
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, seed);
        }

        List<ObjectNode> payloads = new ArrayList<>();
        payloads.add(happy.body());
        for (BodyShape.BodyField field : shape.fields()) {
            if (field.javaType().equals("java.lang.String")
                    && field.name().endsWith("Id") && field.name().length() > 2) {
                ObjectNode variant = happy.body().deepCopy();
                variant.put(field.name(), "missing-" + field.name());
                payloads.add(variant);
            }
        }

        List<WsExchange> exchanges = new ArrayList<>();
        List<CapturedSql> allSql = new ArrayList<>();
        int sequence = 0;
        for (ObjectNode payload : payloads) {
            sequence++;
            String exchangeId = endpoint.id() + "-x" + sequence;
            long logStart = sut.logOffset();

            String responseBody;
            try (StompHelper stomp = StompHelper.connect(sut.baseUri(), endpoint.wsPath(), AWAIT)) {
                stomp.subscribe(endpoint.sendTo());
                stomp.send(endpoint.appPrefix() + endpoint.destination(),
                        Json.mapper().writeValueAsString(payload));
                responseBody = stomp.awaitMessageContaining("", AWAIT);
            }
            Thread.sleep(150);
            long logEnd = sut.logOffset();
            log.info("ws captured {} -> {}", exchangeId,
                    responseBody == null ? "(no message)" : "message");

            List<CapturedSql> sql = captureSql(exchangeId, payload,
                    sut.readLogRange(logStart, logEnd));
            allSql.addAll(sql);
            exchanges.add(new WsExchange(
                    exchangeId, endpoint.id(), payload, endpoint.sendTo(),
                    parseJsonOrNull(responseBody),
                    sql.stream().map(CapturedSql::id).toList()));
        }
        return new WsResult(exchanges, allSql);
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
                                ? BindingOrigin.API_PARAM
                                : BindingOrigin.LITERAL));
            }
            captured.add(new CapturedSql("sql-" + exchangeId + "-" + sequence, exchangeId,
                    statement.kind(), statement.sql(), statement.tableName(), bindings));
        }
        return captured;
    }

    private static JsonNode parseJsonOrNull(String body) {
        if (body == null) {
            return Json.mapper().nullNode();
        }
        try {
            return Json.mapper().readTree(body);
        } catch (Exception e) {
            return Json.mapper().nullNode();
        }
    }
}
