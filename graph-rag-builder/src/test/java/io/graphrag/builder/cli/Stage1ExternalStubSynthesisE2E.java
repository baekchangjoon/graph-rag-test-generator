package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
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
 * 단계1 외부 stub 합성 E2E (REQ-001/002/003/011). order-service를 {@code BuilderCli.build}로 빌드한다.
 * Docker 필요(testcontainers Postgres).
 *
 * <p>SUT 외부-의존 경로: {@code POST /api/orders} type=EXPRESS →
 * {@code InventoryClient.check} → outbound {@code GET /inventory/stock?type=EXPRESS} →
 * 응답 {@code InventoryResponse(Integer available)}. 외부 호출 직후 분기는
 * {@code stock.available() < amount} → 409(insufficient stock).
 *
 * <p>{@code --external-stubs} 없이(빈 stub 디렉터리) 빌드하면 WireMock이 unmatched 404를 내고,
 * B2 재탐색 루프가 인덱싱한 응답 형상으로 {@code {"available":1}} (200)을 합성·등록해 외부 직후
 * 409 분기를 연다. 수동 stub 빌드는 운영자 fixture(available:50)를 그대로 쓴다(CAPTURED).
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class Stage1ExternalStubSynthesisE2E {

    private static final String INVENTORY_PATH = "/inventory/stock";
    private static final String ORDERS_ENDPOINT = "post-api-orders";

    @TempDir
    Path out;

    /** 외부 stub 없이 형상-only 합성으로 외부 호출이 200을 받고 외부 직후 분기에 도달한다. */
    @Test
    @DisplayName("REQ-001: 외부 stub 없이 형상 합성으로 외부 호출 통과")
    void synthesizedStubPassesExternalCall() throws Exception {
        GraphAsset asset = build(noExternalStubs());

        CapturedHttpCall inventory = inventoryCall(asset);
        assertThat(inventory.responseStatus())
                .as("합성 stub이 외부 호출에 200을 반환").isEqualTo(200);
        assertThat(inventory.responseProvenance())
                .as("합성 경유 호출은 SYNTHESIZED").isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
        assertThat(inventory.responseBody())
                .as("형상에서 합성한 minimal valid JSON").contains("available");

        // 외부 호출 직후 분기(available < amount → 409)가 커버리지에 도달한다.
        assertThat(expressStatuses(asset))
                .as("외부 직후 409 분기 도달").contains(409);
    }

    /** 자동 합성 경로의 외부-의존 분기 커버리지가 수동 stub 이상이다. */
    @Test
    @DisplayName("REQ-002: 수동 stub 대비 동등 이상 커버리지")
    void equivalentCoverageToManualStub() throws Exception {
        GraphAsset manual = build(manualStubsDir());
        GraphAsset synthesized = build(noExternalStubs());

        // 외부-의존 분기 = post-api-orders 의 외부 호출 직후 status 집합(특히 409).
        var manualStatuses = expressStatuses(manual);
        var synthStatuses = expressStatuses(synthesized);
        assertThat(synthStatuses)
                .as("합성이 수동 stub의 외부-의존 분기를 모두 커버(누락 없음)")
                .containsAll(manualStatuses);

        // 둘 다 외부 호출 직후 409 분기를 연다(외부 직후 분기 누락 없음).
        assertThat(manualStatuses).contains(409);
        assertThat(synthStatuses).contains(409);

        // 외부 호출 직후 분기집합 다양성이 수동 이상.
        long manualBranchSets = externalBranchSetCount(manual);
        long synthBranchSets = externalBranchSetCount(synthesized);
        assertThat(synthBranchSets)
                .as("합성 외부-의존 path 분기집합 다양성 ≥ 수동")
                .isGreaterThanOrEqualTo(manualBranchSets);
    }

    /** 형상-only 합성은 결정적: 동일 commit 2회 빌드 → 합성 stub body byte-동일 + 동일 커버리지. */
    @Test
    @DisplayName("REQ-003: 동일 commit 2회 빌드 → 합성 stub byte-동일 + 동일 커버리지")
    void deterministicAcrossRuns() throws Exception {
        GraphAsset run1 = build(noExternalStubs());
        GraphAsset run2 = build(noExternalStubs());

        byte[] body1 = inventoryCall(run1).responseBody().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body2 = inventoryCall(run2).responseBody().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body2).as("합성 stub body는 byte-동일(결정적)").isEqualTo(body1);

        assertThat(expressStatuses(run2))
                .as("외부-의존 커버리지(외부 직후 status 집합) 동일")
                .containsExactlyInAnyOrderElementsOf(expressStatuses(run1));
    }

    /** 합성 경유 호출은 SYNTHESIZED, 수동 stub 응답은 CAPTURED로 구분된다. */
    @Test
    @DisplayName("REQ-011: 합성 SYNTHESIZED vs 수동 stub CAPTURED")
    void synthesizedProvenanceTagged() throws Exception {
        GraphAsset synthesized = build(noExternalStubs());
        assertThat(inventoryCall(synthesized).responseProvenance())
                .as("합성 경유 호출 SYNTHESIZED")
                .isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);

        GraphAsset manual = build(manualStubsDir());
        assertThat(inventoryCall(manual).responseProvenance())
                .as("수동 stub(운영자 fixture) 응답 CAPTURED")
                .isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
    }

    // ---- 빌드 헬퍼 ----

    /** 외부 stub 없이(빈 디렉터리) 빌드 설정 — WireMock unmatched 404 → 합성 경로. */
    private Path noExternalStubs() throws Exception {
        return Files.createTempDirectory("stage1-no-stubs");   // .json 없음 → loadStubs no-op
    }

    /** 운영자 수동 stub fixture 디렉터리(available:50). */
    private static Path manualStubsDir() {
        return Path.of(System.getProperty("external.stubs"));
    }

    private GraphAsset build(Path externalStubsDir) throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        Path buildOut = Files.createTempDirectory(out, "build");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        // none 모드: 직렬 전제. 외부 stub 합성은 trace-mode 중립이며 E2E는 외부-의존 경로만 본다.
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

    /** 인덱싱·캡처된 외부 inventory 호출(GET /inventory/stock). */
    private static CapturedHttpCall inventoryCall(GraphAsset asset) {
        return asset.httpCalls().stream()
                .filter(c -> c.urlPath().equals(INVENTORY_PATH))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "외부 inventory 호출이 캡처되지 않음 (외부 호출 자체가 발생/탐색되지 않음). "
                        + "httpCalls=" + asset.httpCalls().stream().map(CapturedHttpCall::urlPath).toList()));
    }

    /** post-api-orders 의 외부 호출 직후 status 집합(외부-의존 분기 프록시). */
    private static List<Integer> expressStatuses(GraphAsset asset) {
        return externalDependentPaths(asset).stream()
                .map(ExploredPath::expectedStatus)
                .distinct()
                .toList();
    }

    /** 외부 호출을 발생시킨 post-api-orders path들(EXPRESS 분기 진입). */
    private static List<ExploredPath> externalDependentPaths(GraphAsset asset) {
        return asset.paths().stream()
                .filter(p -> p.endpointId().equals(ORDERS_ENDPOINT))
                .filter(p -> !p.capturedHttpCallIds().isEmpty())
                .toList();
    }

    /** 외부-의존 path의 분기집합 다양성(서로 다른 branchesTaken 수). */
    private static long externalBranchSetCount(GraphAsset asset) {
        return externalDependentPaths(asset).stream()
                .map(ExploredPath::branchesTaken)
                .distinct()
                .count();
    }
}
