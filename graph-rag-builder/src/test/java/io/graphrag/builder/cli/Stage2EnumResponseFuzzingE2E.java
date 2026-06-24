package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.BranchRef;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단계2 enum 응답 변형 fuzzing E2E (REQ-001, REQ-002, REQ-004). order-service를
 * {@code BuilderCli.build}로 {@code --external-stubs} 없이 빌드한다. Docker 필요(testcontainers Postgres).
 *
 * <p>SUT 외부-의존 경로: {@code POST /api/orders} type=EXPRESS → {@code InventoryClient.check} →
 * outbound {@code GET /inventory/stock} → 응답 {@code InventoryResponse(Integer available,
 * FulfillmentMode mode)}. {@code OrderController.create}는 {@code switch (stock.mode())}로
 * STANDARD/EXPRESS_ONLY/BACKORDER 3 arm으로 갈린다(line {@value #SWITCH_LINE}).
 *
 * <p>단계1은 정렬 첫 상수(STANDARD) 1 arm만 연다. 단계2 변형 루프는 EXPRESS_ONLY/BACKORDER 변형
 * stub을 trace-id 격리로 등록·재invoke해 3 arm을 모두 결정적(no-LLM)으로 연다.
 *
 * <p><b>변형 루프(Task 6·7) 미구현 상태에서는 RED</b>(첫 상수 1 arm만 도달). Task 8까지 약화 금지.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class Stage2EnumResponseFuzzingE2E {

    private static final String INVENTORY_PATH = "/inventory/stock";
    private static final String ORDERS_ENDPOINT = "post-api-orders";
    private static final String SWITCH_CLASS = "io.graphrag.sample.orders.OrderController";
    private static final int SWITCH_LINE = 58;   // switch (stock.mode()) — 3 arm

    @TempDir
    Path out;

    /** enum 3상수를 변형 stub으로 갈아끼워 switch의 STANDARD/EXPRESS_ONLY/BACKORDER 3 arm을 모두 연다. */
    @Test
    @DisplayName("REQ-001: enum 변형으로 모든 arm 도달")
    void enumVariantsReachAllArms() throws Exception {
        GraphAsset asset = build(noExternalStubs());

        // switch (stock.mode()) line의 서로 다른 분기(arm) 인덱스 수 = 도달한 arm 수.
        long switchArmsCovered = externalDependentPaths(asset).stream()
                .flatMap(p -> p.branchesTaken().stream())
                .filter(b -> b.classFqn().equals(SWITCH_CLASS) && b.line() == SWITCH_LINE)
                .map(BranchRef::branchIndex)
                .distinct()
                .count();
        assertThat(switchArmsCovered)
                .as("FulfillmentMode switch 3 arm(STANDARD/EXPRESS_ONLY/BACKORDER) 모두 도달")
                .isGreaterThanOrEqualTo(3);

        // 외부 직후 분기집합 다양성: 단계1(1 arm)보다 증가(3 arm 분기집합).
        assertThat(externalBranchSetCount(asset))
                .as("외부-의존 path 분기집합 다양성 ≥ 3(arm별 구분)")
                .isGreaterThanOrEqualTo(3);
    }

    /** 변형 stub 경유 캡처도 SYNTHESIZED로 태깅된다(REQ-004). */
    @Test
    @DisplayName("REQ-004: 변형 stub 캡처 SYNTHESIZED")
    void variantStubCapturesAreSynthesized() throws Exception {
        GraphAsset asset = build(noExternalStubs());

        List<CapturedHttpCall> inventoryCalls = asset.httpCalls().stream()
                .filter(c -> c.urlPath().equals(INVENTORY_PATH))
                .toList();
        assertThat(inventoryCalls)
                .as("외부 inventory 호출이 변형 포함 다수 캡처됨").isNotEmpty();
        assertThat(inventoryCalls)
                .as("변형 stub 경유 캡처도 SYNTHESIZED")
                .allMatch(c -> c.responseProvenance() == CapturedHttpCall.Provenance.SYNTHESIZED);
    }

    /** 변형 생성·측정 순서 결정적: 2회 빌드 동일 arm 도달 + 동일 외부-의존 분기집합. */
    @Test
    @DisplayName("REQ-002: 2회 빌드 동일 변형·커버리지(결정적)")
    void deterministicAcrossRuns() throws Exception {
        GraphAsset run1 = build(noExternalStubs());
        GraphAsset run2 = build(noExternalStubs());

        assertThat(externalDependentStatuses(run2))
                .as("외부-의존 status 집합 동일")
                .containsExactlyInAnyOrderElementsOf(externalDependentStatuses(run1));
        assertThat(externalBranchSetCount(run2))
                .as("외부-의존 분기집합 다양성 동일")
                .isEqualTo(externalBranchSetCount(run1));
    }

    // ---- 빌드 헬퍼 (Stage1ExternalStubSynthesisE2E 패턴 재사용) ----

    private Path noExternalStubs() throws Exception {
        return Files.createTempDirectory("stage2-no-stubs");   // .json 없음 → loadStubs no-op
    }

    private GraphAsset build(Path externalStubsDir) throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        Path buildOut = Files.createTempDirectory(out, "build");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        return BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, buildOut,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, externalStubsDir,
                Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}"),
                null, null, authConfig, false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "none", null, false));
    }

    // ---- 단언 헬퍼 ----

    private static List<ExploredPath> externalDependentPaths(GraphAsset asset) {
        return asset.paths().stream()
                .filter(p -> p.endpointId().equals(ORDERS_ENDPOINT))
                .filter(p -> !p.capturedHttpCallIds().isEmpty())
                .toList();
    }

    private static List<Integer> externalDependentStatuses(GraphAsset asset) {
        return externalDependentPaths(asset).stream()
                .map(ExploredPath::expectedStatus)
                .distinct()
                .toList();
    }

    private static long externalBranchSetCount(GraphAsset asset) {
        return externalDependentPaths(asset).stream()
                .map(ExploredPath::branchesTaken)
                .distinct()
                .count();
    }
}
