package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlLogParser;
import io.graphrag.builder.coverage.CoverageClient;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Json;
import io.graphrag.model.SqlBinding;
import io.graphrag.model.TableSchema;
import io.graphrag.model.WsEndpoint;
import io.graphrag.model.WsExchange;
import io.graphrag.testlib.api.StompHelper;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * STOMP endpoint의 결정적 메시지 교환 캡처 (roadmap 3.2).
 * happy + missing-ref 변형의 2종 payload. WS 분기 탐색은 보류
 * (docs/decisions/stomp-capture.md).
 *
 * <p>각 교환의 핸들러 실행 커버리지를 dump delta로 떠서 {@link WsResult#cumulativeExec()}로 반환한다
 * (BuilderCli가 runWideExec에 병합 → WS 핸들러 커버도 exploration 지표에 포함).
 */
public class WsCaptureRunner {

    private static final Logger log = LoggerFactory.getLogger(WsCaptureRunner.class);
    private static final Duration AWAIT = Duration.ofSeconds(8);

    public record WsResult(List<WsExchange> exchanges, List<CapturedSql> sql,
                           ExecutionDataStore cumulativeExec) {
    }

    private final SutHandle sut;
    private final Connection connection;
    private final DbConfig.Type dbType;
    private final CoverageClient coverage;

    public WsCaptureRunner(SutHandle sut, Connection connection, DbConfig.Type dbType,
                           CoverageClient coverage) {
        this.sut = sut;
        this.connection = connection;
        this.dbType = dbType;
        this.coverage = coverage;
    }

    public WsResult run(WsEndpoint endpoint, BodyShape shape, List<TableSchema> tables)
            throws Exception {
        SynthesizedInput happy = new SampleInputSynthesizer().synthesize(shape, tables);
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, dbType, seed);
        }

        List<JsonNode> payloads = new ArrayList<>();
        payloads.add(happy.body());
        // missing-ref 변종은 ObjectNode payload 전제 — 컬렉션(array) body는 happy-only.
        if (happy.body() instanceof ObjectNode happyObj) {
            for (BodyShape.BodyField field : shape.fields()) {
                if (field.javaType().equals("java.lang.String")
                        && field.name().endsWith("Id") && field.name().length() > 2) {
                    ObjectNode variant = happyObj.deepCopy();
                    variant.put(field.name(), "missing-" + field.name());
                    payloads.add(variant);
                }
            }
        }

        List<WsExchange> exchanges = new ArrayList<>();
        List<CapturedSql> allSql = new ArrayList<>();
        ExecutionDataStore cumulativeExec = new ExecutionDataStore();
        coverage.dump(true);   // baseline: boot/seed 구간 제거 후 교환별 핸들러 delta만 측정
        int sequence = 0;
        for (JsonNode payload : payloads) {
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
            coverage.dump(true).accept(cumulativeExec);   // 이 교환의 핸들러 실행 커버
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
        return new WsResult(exchanges, allSql, cumulativeExec);
    }

    private List<CapturedSql> captureSql(String exchangeId, JsonNode payload, String logSegment) {
        Set<String> payloadValues = EndpointExplorationRunner.collectBodyValues(payload);
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
                                : BindingOrigin.LITERAL,
                        statement.bindingTableForPosition(binding.position())));
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
