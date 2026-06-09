package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.capture.HibernateSqlLogParser;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.env.SutProcess;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.SqlBinding;
import io.graphrag.model.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * happy-path 1회 실행 + sink 캡처 (Phase 0).
 * seed INSERT(빌더 직접) → 로그 오프셋 마커 → HTTP 호출 → 로그 파싱 → 사실 생성.
 */
public class PathCaptureRunner {

    private static final Logger log = LoggerFactory.getLogger(PathCaptureRunner.class);

    public record CaptureResult(ExploredPath path, List<CapturedSql> sql) {
    }

    public CaptureResult capture(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                                 SutProcess sut, Connection connection) throws Exception {
        SynthesizedInput input = new SampleInputSynthesizer().synthesize(shape, tables);

        for (SynthesizedInput.SeedRow seed : input.seeds()) {
            insertSeed(connection, seed);
        }

        long offset = sut.logOffset();
        String requestBody = Json.mapper().writeValueAsString(input.body());

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(sut.baseUri() + endpoint.path()))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        log.info("captured {} {} -> {}", endpoint.httpMethod(), endpoint.path(), response.statusCode());

        // 로그 flush 대기 후 새 구간만 파싱
        Thread.sleep(500);
        List<ParsedSql> parsed = HibernateSqlLogParser.parse(sut.readLogFrom(offset));

        String pathId = endpoint.id() + "-happy";
        Set<String> apiValues = bodyValues(input.body());
        List<CapturedSql> captured = new ArrayList<>();
        List<String> sqlIds = new ArrayList<>();
        int sequence = 0;
        for (ParsedSql statement : parsed) {
            sequence++;
            String sqlId = "sql-" + pathId + "-" + sequence;
            sqlIds.add(sqlId);
            List<SqlBinding> bindings = new ArrayList<>();
            for (ParsedSql.Binding binding : statement.bindings()) {
                bindings.add(new SqlBinding(
                        binding.position(),
                        statement.columnForPosition(binding.position()),
                        binding.value(),
                        apiValues.contains(binding.value())
                                ? BindingOrigin.API_PARAM
                                : BindingOrigin.LITERAL));
            }
            captured.add(new CapturedSql(sqlId, pathId, statement.kind(),
                    statement.sql(), statement.tableName(), bindings));
        }

        JsonNode responseJson = parseJsonOrNull(response.body());
        ExploredPath path = new ExploredPath(pathId, endpoint.id(), input.body(),
                response.statusCode(), responseJson, sqlIds);
        return new CaptureResult(path, captured);
    }

    private static void insertSeed(Connection connection, SynthesizedInput.SeedRow seed) throws Exception {
        String placeholders = String.join(", ", seed.columns().stream().map(c -> "?").toList());
        String sql = "INSERT INTO " + seed.table() + " (" + String.join(", ", seed.columns())
                + ") VALUES (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < seed.values().size(); i++) {
                statement.setObject(i + 1, seed.values().get(i));
            }
            statement.executeUpdate();
        }
        log.info("seeded: {} {}", seed.table(), seed.values());
    }

    private static Set<String> bodyValues(JsonNode body) {
        Set<String> values = new HashSet<>();
        body.fields().forEachRemaining(entry -> values.add(entry.getValue().asText()));
        return values;
    }

    private static JsonNode parseJsonOrNull(String body) {
        try {
            return Json.mapper().readTree(body);
        } catch (Exception e) {
            return Json.mapper().nullNode();
        }
    }
}
