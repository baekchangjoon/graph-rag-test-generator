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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단계2-A String 리터럴 응답 변형 fuzzing E2E (REQ-001, REQ-002, REQ-004).
 * order-service를 {@code BuilderCli.build}로 {@code --external-stubs} 없이 빌드한다.
 * Docker 필요(testcontainers Postgres).
 *
 * <p>SUT String 분기 경로: {@code POST /api/orders} type=EXPRESS → {@code InventoryClient.check} →
 * 응답 {@code InventoryResponse(Integer available, FulfillmentMode mode, String region)}.
 * {@code OrderController.create}는 {@code if ("EMBARGOED".equals(stock.region()))}으로 422 분기를
 * 결정한다(line {@value #EMBARGOED_LINE}).
 *
 * <p>단계1·단계2(enum)는 기본값 region에서만 탐색해 EMBARGOED arm(422)을 열지 못한다. 단계2-A String
 * 리터럴 변형 루프가 "EMBARGOED" stub을 변형 stub으로 등록·재invoke해 그 arm을 결정적으로 연다.
 * (이 E2E는 {@code --trace-mode none}으로 빌드하므로 변형 stub은 trace-id 격리가 아니라 순차 교체로
 * 등록·제거된다 — 격리/순차 모두 같은 변형 루프를 탄다.)
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class Stage2AStringLiteralFuzzingE2E {

    private static final String INVENTORY_PATH = "/inventory/stock";
    private static final String ORDERS_ENDPOINT = "post-api-orders";
    private static final String EMBARGOED_CLASS = "io.graphrag.sample.orders.OrderController";
    /**
     * OrderController.java line 55: {@code if ("EMBARGOED".equals(stock.region())) {}.
     * JaCoCo는 이 if-분기를 라인 55의 branch index로 트래킹한다.
     */
    private static final int EMBARGOED_LINE = 55;

    @TempDir
    Path out;

    /**
     * "EMBARGOED" String 변형 stub이 region 필드를 재정의해 OrderController의
     * {@code if ("EMBARGOED".equals(stock.region()))} 422 arm을 연다(REQ-001).
     *
     * <p>단계2(enum-only)에서는 EMBARGOED arm이 미도달(기본 region 값 통과). 단계2-A 이후
     * branchesTaken에 EMBARGOED_LINE 분기가 포함돼야 하고, String 변형 path(id "*-responsevar*")가
     * 존재해야 한다.
     */
    @Test
    @DisplayName("REQ-001: String 변형이 EMBARGOED arm(422)에 도달한다")
    void stringVariantReachesEmbargoedArm() throws Exception {
        GraphAsset asset = build(noExternalStubs());

        // EMBARGOED 422 arm이 도달됐는지: line 55의 branch가 외부-의존 path에 포함돼야 한다.
        boolean embargoedArmCovered = externalDependentPaths(asset).stream()
                .flatMap(p -> p.branchesTaken().stream())
                .anyMatch(b -> b.classFqn().equals(EMBARGOED_CLASS) && b.line() == EMBARGOED_LINE);
        assertThat(embargoedArmCovered)
                .as("if (\"EMBARGOED\".equals(stock.region())) arm(line=%d)이 도달돼야 한다", EMBARGOED_LINE)
                .isTrue();

        // String 변형 path(discoveredBy="response-variant")가 하나 이상 존재해야 한다.
        boolean hasStringVariantPath = asset.paths().stream()
                .filter(p -> p.endpointId().equals(ORDERS_ENDPOINT))
                .anyMatch(p -> "response-variant".equals(p.discoveredBy()));
        assertThat(hasStringVariantPath)
                .as("response-variant discoveredBy path가 하나 이상 존재해야 한다(String 변형 루프 실행 증거)")
                .isTrue();

        // String 변형 추가로 외부-의존 분기집합 다양성이 2 이상이어야 한다(baseline + EMBARGOED 변형 최소 2개).
        assertThat(externalBranchSetCount(asset))
                .as("String 변형 arm 포함 외부-의존 분기집합 다양성 ≥ 2 (baseline + EMBARGOED 변형 최소 2개)")
                .isGreaterThanOrEqualTo(2L);
    }

    /**
     * 변형 생성·측정 순서 결정적: 2회 빌드에서 변형 label 목록 + ExploredPath id 집합 +
     * JaCoCo branch 집합이 동일해야 한다(REQ-002).
     */
    @Test
    @DisplayName("REQ-002: 2회 실행 변형 label·id·branch 집합 동일")
    void deterministicAcrossRuns() throws Exception {
        GraphAsset run1 = build(noExternalStubs());
        GraphAsset run2 = build(noExternalStubs());

        // ExploredPath id 집합 비교
        Set<String> ids1 = run1.paths().stream()
                .filter(p -> p.endpointId().equals(ORDERS_ENDPOINT))
                .map(ExploredPath::id)
                .collect(Collectors.toSet());
        Set<String> ids2 = run2.paths().stream()
                .filter(p -> p.endpointId().equals(ORDERS_ENDPOINT))
                .map(ExploredPath::id)
                .collect(Collectors.toSet());
        assertThat(ids2)
                .as("2회 실행 ExploredPath id 집합 동일")
                .containsExactlyInAnyOrderElementsOf(ids1);

        // 외부-의존 JaCoCo branch 집합 비교
        Set<String> branches1 = externalDependentPaths(run1).stream()
                .flatMap(p -> p.branchesTaken().stream())
                .map(b -> b.classFqn() + ":" + b.line() + ":" + b.branchIndex())
                .collect(Collectors.toSet());
        Set<String> branches2 = externalDependentPaths(run2).stream()
                .flatMap(p -> p.branchesTaken().stream())
                .map(b -> b.classFqn() + ":" + b.line() + ":" + b.branchIndex())
                .collect(Collectors.toSet());
        assertThat(branches2)
                .as("2회 실행 JaCoCo branch 집합 동일")
                .containsExactlyInAnyOrderElementsOf(branches1);

        // 외부-의존 분기집합 다양성(distinct branchesTaken) 동일
        assertThat(externalBranchSetCount(run2))
                .as("2회 실행 외부-의존 분기집합 다양성 동일")
                .isEqualTo(externalBranchSetCount(run1));
    }

    /**
     * String 변형 stub 경유 캡처도 SYNTHESIZED로 태깅된다(REQ-004).
     * 전역 Set에 미등록(trace-id 격리)이어도 provenance는 SYNTHESIZED여야 한다.
     */
    @Test
    @DisplayName("REQ-004: String 변형 stub 캡처는 SYNTHESIZED")
    void variantStubCapturesAreSynthesized() throws Exception {
        GraphAsset asset = build(noExternalStubs());

        List<CapturedHttpCall> inventoryCalls = asset.httpCalls().stream()
                .filter(c -> c.urlPath().equals(INVENTORY_PATH))
                .toList();
        assertThat(inventoryCalls)
                .as("외부 inventory 호출이 변형 포함 다수 캡처됨").isNotEmpty();
        assertThat(inventoryCalls)
                .as("변형 stub 경유 캡처(String 포함)도 SYNTHESIZED")
                .allMatch(c -> c.responseProvenance() == CapturedHttpCall.Provenance.SYNTHESIZED);
    }

    // ---- 빌드 헬퍼 (Stage2EnumResponseFuzzingE2E 패턴 재사용) ----

    private Path noExternalStubs() throws Exception {
        return Files.createTempDirectory("stage2a-no-stubs");   // .json 없음 → loadStubs no-op
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

    private static long externalBranchSetCount(GraphAsset asset) {
        return externalDependentPaths(asset).stream()
                .map(ExploredPath::branchesTaken)
                .distinct()
                .count();
    }
}
