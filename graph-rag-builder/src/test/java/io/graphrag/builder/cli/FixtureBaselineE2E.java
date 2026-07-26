package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-028: transfers 깊은-happy fixture 착륙 + outer red 고정.
 *
 * <p>fixture EP 4종(fulfillment/transfers/invoices/quotas)이 착륙된 order-service를 현행
 * (트리플 미적용) 빌드로 탐색하면, {@code POST /api/transfers}의 다중 가드(계좌 존재/잔액/중첩
 * items/외부 fraud 응답)를 모두 통과하는 2xx happy path가 아직 합성되지 않아야 한다. 이 테스트는
 * 그 "미도달"을 고정하는 outer red 전제이며, 현행 코드에서 즉시 green이어야 한다(red가 되면 fixture
 * 가드가 현행 합성으로 뚫린 것이므로 가드를 강화해 다시 고정한다). Phase A(삼중 합성) 도입 후에는
 * {@code GRB_TRIAL=off}가 같은 조건의 A/B 대조군 역할을 한다.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class FixtureBaselineE2E {

    @TempDir
    Path out;

    @Test
    @DisplayName("REQ-028: 현행 합성으로 transfers 깊은-happy 2xx 미도달 (outer red 전제)")
    void req028_currentSynthesisCannotReachDeepHappy() throws Exception {
        GraphAsset graph = buildOrderServiceGraph();
        List<ExploredPath> transferPaths = graph.paths().stream()
                .filter(p -> p.endpointId().equals("post-api-transfers")).toList();
        // vacuous-통과 방지: endpoint 자체가 탐색되지 않아도(회귀로 그래프에서 사라져도) 아래
        // noneMatch는 빈 스트림에 자명하게 참이 되어 outer-red를 감지 못한다. transferPaths가
        // 비어있지 않음을 먼저 단언해 "가드에 막혀 2xx 없음"과 "endpoint 자체가 없음"을 구분한다.
        assertThat(transferPaths).isNotEmpty();
        assertThat(transferPaths).noneMatch(p -> p.expectedStatus() / 100 == 2
                && p.outcome() == Outcome.Kind.SUCCESS);
    }

    /**
     * 신설 헬퍼: {@code BuilderIntegrationTest}의 부팅 계약(-Dsut.jar/-Dsut.src 시스템 프로퍼티,
     * Docker/Testcontainers, WireMock env 주입)을 그대로 재사용해 order-service jar를 트리플
     * 미적용 현행 빌드로 탐색한다. {@code EXTERNAL_FRAUD_URL}은 기존 {@code EXTERNAL_INVENTORY_URL}과
     * 동일한 분석 WireMock({@code {{wiremock}}})으로 주입한다.
     */
    private GraphAsset buildOrderServiceGraph() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        return BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, out,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null,
                Path.of(System.getProperty("external.stubs")),
                Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}", "EXTERNAL_FRAUD_URL", "{{wiremock}}"),
                null, null, authConfig, false, true, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(), "none", null, false));
    }
}
