package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.GraphAsset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** --endpoint 선택자 수용 테스트: 선택한 단위만 탐색(부분 그래프), 정적 인덱스는 풀 유지. Docker 필요. */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class BuilderEndpointSelectorTest {

    @TempDir
    Path out;

    @Test
    void endpointSelector_scopesExplorationButKeepsFullStaticIndex() throws Exception {
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
                null, null, authConfig, false, true, null,
                null, io.graphrag.model.RequestHeaders.empty(),
                List.of("POST /api/orders"), "log"));   // 단일 엔드포인트만 탐색, base 없음 → 부분 그래프

        // 1) scoped facts: 탐색된 path는 전부 선택한 엔드포인트(post-api-orders) 소속이어야 한다.
        assertThat(asset.paths()).isNotEmpty();
        assertThat(asset.paths()).extracting(p -> p.endpointId())
                .containsOnly("post-api-orders");

        // 2) 계약 — 정적 메타데이터는 필터링되지 않는다: endpoints() 는 여전히 1개 초과(풀 인덱스).
        assertThat(asset.endpoints()).extracting(e -> e.id())
                .contains("post-api-orders");
        assertThat(asset.endpoints().size()).isGreaterThan(1);
    }
}
