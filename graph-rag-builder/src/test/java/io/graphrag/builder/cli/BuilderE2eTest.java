package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.SqlBinding;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 order-service jar에 대한 빌더 전 사이클 (Phase 1: 탐색 + MyBatis). Docker 필요. */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class BuilderE2eTest {

    @TempDir
    Path out;

    @Test
    void build_exploresMultiplePathsAndCapturesBothOrms() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", java.util.List.of());

        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, out,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null,
                Path.of(System.getProperty("external.stubs")),
                java.util.Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}"),
                null, null, authConfig, false, null));

        // auth 추가 + GET read-path 활성화로 인덱싱되는 엔드포인트 (id 정렬 순)
        assertThat(asset.endpoints()).extracting(e -> e.id())
                .containsExactly("delete-api-bookings-id", "get-api-bookings-id",
                        "get-api-orders", "get-api-orders-id",
                        "post-api-auth-login", "post-api-bookings", "post-api-orders",
                        "post-api-orders-search", "post-api-promo", "put-api-bookings-id");

        // JPA endpoint: 201/404/400 path가 모두 발견된다 (Phase 1 메트릭의 핵심)
        List<ExploredPath> orderPaths = pathsOf(asset, "post-api-orders");
        assertThat(orderPaths.stream().map(ExploredPath::expectedStatus).distinct())
                .contains(201, 404, 400);

        // MyBatis endpoint: 200 + 400 path와 동적 SQL 캡처
        List<ExploredPath> searchPaths = pathsOf(asset, "post-api-orders-search");
        assertThat(searchPaths.stream().map(ExploredPath::expectedStatus).distinct())
                .contains(200, 400);
        ExploredPath searchHappy = searchPaths.stream()
                .filter(p -> p.expectedStatus() == 200).findFirst().orElseThrow();
        List<CapturedSql> searchSql = asset.sql().stream()
                .filter(s -> s.pathId().equals(searchHappy.id())).toList();
        assertThat(searchSql).anyMatch(s -> s.tableName().equals("orders")
                && s.sqlKind().equals("SELECT"));

        // origin 판정은 Phase 0과 동일하게 유지된다
        ExploredPath orderHappy = orderPaths.stream()
                .filter(p -> p.expectedStatus() == 201).findFirst().orElseThrow();
        CapturedSql insert = asset.sql().stream()
                .filter(s -> s.pathId().equals(orderHappy.id())
                        && s.sqlKind().equals("INSERT") && s.tableName().equals("orders"))
                .findFirst().orElseThrow();
        assertThat(insert.bindings())
                .filteredOn(b -> b.column().equals("status"))
                .extracting(SqlBinding::origin).containsExactly(BindingOrigin.LITERAL);

        // 분기/엔진/제약 메타데이터
        assertThat(orderHappy.branchesTaken()).isNotEmpty();
        assertThat(orderHappy.discoveredBy()).isIn("heuristic", "fuzzer");
        assertThat(orderPaths.stream().filter(p -> p.expectedStatus() == 400).findFirst()
                .orElseThrow().constraints())
                .anyMatch(c -> c.contains("userId() == null"));

        // Phase 2: EXPRESS 분기 → 외부 HTTP 캡처 (literal 변이로 도달)
        List<ExploredPath> expressPaths = orderPaths.stream()
                .filter(p -> !p.capturedHttpCallIds().isEmpty()).toList();
        assertThat(expressPaths).isNotEmpty();
        assertThat(orderPaths.stream().map(ExploredPath::expectedStatus)).contains(409);
        var httpCall = asset.httpCalls().stream()
                .filter(c -> c.pathId().equals(expressPaths.get(0).id()))
                .findFirst().orElseThrow();
        assertThat(httpCall.method()).isEqualTo("GET");
        assertThat(httpCall.urlPath()).isEqualTo("/inventory/stock");
        assertThat(httpCall.query()).containsEntry("type", "EXPRESS");
        assertThat(httpCall.responseBody()).contains("available");
        assertThat(httpCall.consumedFields()).containsExactly("available");
        // OTEL javaagent가 inbound baggage를 outbound로 전파했다 (docs/06 격리 기반)
        assertThat(httpCall.baggagePropagated()).isTrue();

        // Phase 3: STOMP endpoint + 메시지 교환 캡처 (happy/missing-ref)
        assertThat(asset.wsEndpoints()).extracting(w -> w.id()).containsExactly("ws-orders-count");
        var wsExchanges = asset.wsExchanges();
        assertThat(wsExchanges).hasSize(2);
        var wsHappy = wsExchanges.get(0);
        assertThat(wsHappy.payload().get("userId").asText()).isEqualTo("probe-userId");
        assertThat(wsHappy.response().get("userId").asText()).isEqualTo("probe-userId");
        assertThat(wsHappy.response().has("count")).isTrue();
        // WS 핸들러의 파생 쿼리 SQL도 캡처된다
        assertThat(asset.sql().stream().filter(s -> s.pathId().equals(wsHappy.id())))
                .anyMatch(s -> s.sqlKind().equals("SELECT") && s.tableName().equals("orders")
                        && s.bindings().stream().anyMatch(b ->
                                b.origin() == BindingOrigin.API_PARAM
                                        && b.value().equals("probe-userId")));

        // read-path: GET /api/orders/{id} 가 탐색되어 2xx path + FK 부모 시드를 남긴다 (C#3)
        List<ExploredPath> getByIdPaths = pathsOf(asset, "get-api-orders-id");
        assertThat(getByIdPaths).isNotEmpty();
        ExploredPath getByIdHappy = getByIdPaths.stream()
                .filter(p -> p.expectedStatus() / 100 == 2).findFirst().orElseThrow();
        // 2xx로 도달하려면 대상 order + 그 FK 부모 user가 시드되어 있어야 한다
        List<RequiredSeed> getByIdSeeds = asset.seeds().stream()
                .filter(s -> s.pathId().equals(getByIdHappy.id())).toList();
        assertThat(getByIdSeeds).isNotEmpty();
        assertThat(getByIdSeeds).extracting(RequiredSeed::table)
                .contains("orders", "users");
        // GET path는 read이므로 INSERT가 아닌 SELECT SQL을 캡처한다
        assertThat(asset.sql().stream().filter(s -> s.pathId().equals(getByIdHappy.id())))
                .anyMatch(s -> s.sqlKind().equals("SELECT") && s.tableName().equals("orders"));

        // MyBatis mapper 사실 + still_missing 리포트
        assertThat(asset.mappers()).extracting(m -> m.statementId()).contains("search");
        assertThat(Files.exists(out.resolve("exploration-report.json"))).isTrue();
        String report = Files.readString(out.resolve("exploration-report.json"));
        assertThat(report).contains("post-api-orders").contains("totalBranches");
    }

    private static List<ExploredPath> pathsOf(GraphAsset asset, String endpointId) {
        return asset.paths().stream().filter(p -> p.endpointId().equals(endpointId)).toList();
    }
}
