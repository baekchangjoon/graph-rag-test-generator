package io.graphrag.builder.cli;

import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
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

        GraphAsset asset = BuilderCli.build(sutSrc, sutResources, sutJar, out,
                "order-service", "test", "postgres:15", 60, null);

        assertThat(asset.endpoints()).extracting(e -> e.id())
                .containsExactly("post-api-orders", "post-api-orders-search");

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
