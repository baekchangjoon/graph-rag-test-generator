package io.graphrag.generator;

import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.model.AuthMode;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #62 flaky 수정의 생성기 end-to-end 검증(템플릿 렌더링 포함).
 *  - Fix#1: GET by-id 404 시나리오의 path id 가 도달불가 큰 id 로 렌더된다.
 *  - Fix#3: 성공 POST(autoIncrement PK, param-bound cleanup 없음)가 응답 id 를 캡처해 deferDelete 한다.
 */
class GeneratorFlakyFixTest {

    private static GraphRagClient client(Endpoint ep, List<ExploredPath> paths,
                                         java.util.Map<String, List<CapturedSql>> sqlByPath,
                                         List<TableSchema> tables) {
        return new GraphRagClient() {
            public Endpoint endpoint(String id) { return ep; }
            public ExploredPath path(String id) {
                return paths.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
            }
            public List<ExploredPath> pathsForEndpoint(String e) { return paths; }
            public List<CapturedSql> sqlForPath(String p) { return sqlByPath.getOrDefault(p, List.of()); }
            public List<io.graphrag.model.CapturedHttpCall> httpCallsForPath(String p) { return List.of(); }
            public boolean hasWsEndpoint(String id) { return false; }
            public io.graphrag.model.WsEndpoint wsEndpoint(String id) { throw new UnsupportedOperationException(); }
            public List<io.graphrag.model.WsExchange> wsExchangesFor(String w) { return List.of(); }
            public io.graphrag.model.WsExchange wsExchange(String id) { throw new UnsupportedOperationException(); }
            public boolean hasKafkaConsumer(String id) { return false; }
            public io.graphrag.model.KafkaConsumer kafkaConsumer(String id) { throw new UnsupportedOperationException(); }
            public List<io.graphrag.model.KafkaExchange> kafkaExchangesFor(String c) { return List.of(); }
            public List<TableSchema> tables() { return tables; }
            public List<io.graphrag.model.RequiredSeed> seedsForPath(String p) { return List.of(); }
        };
    }

    private static String onlyJava(GenerationResult r) {
        return r.files().stream().filter(f -> f.relativePath().endsWith(".java"))
                .map(io.graphrag.model.GeneratedFile::content).findFirst().orElseThrow();
    }

    @Test
    void fix1_getById404_rendersUnreachableAbsentId() {
        Endpoint ep = new Endpoint("get-api-bookings-id", "GET", "/api/bookings/{id}",
                "x.BookingController", "getById",
                List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);
        var input = Json.mapper().createObjectNode();
        input.put("id", 1);
        ExploredPath p404 = new ExploredPath("get-api-bookings-id-s404-1", "get-api-bookings-id",
                input, 404, Json.mapper().nullNode(),
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of());

        GenerationResult r = new Generator(client(ep, List.of(p404), java.util.Map.of(), List.of()))
                .generate(new GenerationRequest("get-api-bookings-id", null,
                        "BookingsGetByIdTest", "io.graphrag.generated", AuthMode.DISABLED));
        String java = onlyJava(r);

        assertThat(java).contains(".get(\"/api/bookings/2000000000\")");
        assertThat(java).doesNotContain(".get(\"/api/bookings/1\")");
    }

    @Test
    void fix3_postCreate_autoIncPk_capturesResponseIdAndDefersDelete() {
        Endpoint ep = new Endpoint("post-api-bookings", "POST", "/api/bookings",
                "x.BookingController", "create",
                List.of(new EndpointParam("req", "x.BookingController$Req", ParamKind.BODY)), false);
        var input = Json.mapper().createObjectNode();
        input.put("customerEmail", "probe");
        var resp = Json.mapper().createObjectNode();
        resp.put("id", 1);
        resp.put("status", "CONFIRMED");
        ExploredPath p201 = new ExploredPath("post-api-bookings-happy", "post-api-bookings",
                input, 201, resp,
                List.of("sql-1"), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of());
        CapturedSql insert = new CapturedSql("sql-1", "post-api-bookings-happy", "INSERT",
                "insert into bookings (customer_email) values (?)", "bookings", List.of());
        TableSchema bookings = new TableSchema("bookings", List.of(
                new ColumnSchema("id", "BIGINT", false, true, true),
                new ColumnSchema("customer_email", "VARCHAR", true, false, false)),
                List.of(), List.of());

        GenerationResult r = new Generator(client(ep, List.of(p201),
                java.util.Map.of("post-api-bookings-happy", List.of(insert)), List.of(bookings)))
                .generate(new GenerationRequest("post-api-bookings", null,
                        "BookingsPostTest", "io.graphrag.generated", AuthMode.DISABLED));
        String java = onlyJava(r);

        assertThat(java).contains("io.restassured.response.Response __resp =");
        // varargs+제네릭 추론 함정 회피: (Object) 캐스트가 없으면 path()가 Object[]로 추론되어
        // 런타임 ClassCastException 이 난다(Integer → Object[]). 캐스트를 단언으로 잠근다.
        assertThat(java).contains(
                "scope.jdbc().deferDelete(\"DELETE FROM bookings WHERE id = ?\", (Object) __resp.path(\"id\"));");
    }
}
