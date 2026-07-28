package io.graphrag.builder.capture;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.cli.BuildConfig;
import io.graphrag.builder.cli.BuilderCli;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.GraphAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-S015-006/007: redirect 없이 OTEL CLIENT span으로 발견된 외부 호출이 형상-시드 body를 가진
 * SYNTHESIZED stub으로 graph에 기록됨을 full-pipeline로 검증한다.
 *
 * <p>order-service를 OTEL 모드로 빌드. {@code EXTERNAL_INVENTORY_URL}을 테스트가 띄운 호스트 stub의
 * 직접 URL로 주고(WireMock 치환·{@code {{wiremock}}}·externalStubsDir 합성 미사용), egress span으로
 * 발견된 {@code GET /inventory/stock}이 {@code captureHttpCalls} enrichment를 거쳐 SYNTHESIZED·비어있지
 * 않은 형상 body로 {@code GraphAsset.httpCalls()}에 기록되는지 본다.
 *
 * <p>sleuth 모드는 enrichment 경로(EgressStubComposer)가 trace-mode 중립이라 동일 코드를 타므로,
 * 모드별 발견은 1순위 E2E(Otel/Sleuth EgressDiscoveryE2E)가, enrichment는 in-process
 * {@code CaptureHttpCallsEgressEnrichTest}가 커버한다.
 *
 * <p>Docker(Testcontainers Postgres) + {@code -Dsut.jar=...} {@code -Dsut.src=...} 필요. 미충족 시 skip.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EgressStatusAgnosticStubE2E {

    private static final String INVENTORY_RESPONSE = "{\"available\":5,\"mode\":\"EXPRESS\"}";

    @TempDir
    Path out;

    private HttpServer inventoryStub;
    private String inventoryUrl;

    @BeforeAll
    void startInventoryStub() throws Exception {
        inventoryStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inventoryStub.createContext("/inventory/stock", exchange -> {
            byte[] body = INVENTORY_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        inventoryStub.start();
        inventoryUrl = "http://127.0.0.1:" + inventoryStub.getAddress().getPort();
    }

    @AfterAll
    void stopInventoryStub() {
        // 이 테스트가 띄운 호스트 stub만 정리(REQ-S015-007). SUT 프로세스·Postgres 컨테이너 수명은
        // BuilderCli.build 내부(Testcontainers Ryuk reap)가 관리한다.
        if (inventoryStub != null) {
            inventoryStub.stop(0);
        }
    }

    @Test
    @DisplayName("REQ-S015-006/REQ-F012-001: otel redirect 없이 발견된 GET /inventory/stock → CONTRACT 값-충실 body(소비 리터럴)")
    void otelEgressBecomesSynthesizedStub() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        Path buildOut = Files.createTempDirectory(out, "build");
        Path noExternalStubs = Files.createTempDirectory(out, "egress-no-stubs");   // @TempDir 하위 → 자동 정리; .json 없음 → 합성/redirect 미사용

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, buildOut,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, noExternalStubs,
                Map.of("EXTERNAL_INVENTORY_URL", inventoryUrl),   // 직접 URL — redirect 없음
                null, null, authConfig, false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "otel", null, false));

        // egress-assertion 산출물(pathId 접미 -egressassert)은 같은 urlPath를 갖는 계약 산출물이라
        // 제외한다 — 없으면 findFirst()가 리스트 순서에 따라 그쪽을 집을 수 있다.
        CapturedHttpCall inventory = asset.httpCalls().stream()
                .filter(c -> c.urlPath().endsWith("/inventory/stock"))
                .filter(c -> !c.pathId().endsWith("-egressassert"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no /inventory/stock in httpCalls: "
                                + asset.httpCalls().stream().map(CapturedHttpCall::urlPath).toList()));

        // REQ-012 supersession: order-service의 InventoryResponse.region에 소비 코드 equals-family
        // 리터럴("EMBARGOED")이 있어, span-발견 호출이 형상-시드(SYNTHESIZED)를 넘어 값-충실 CONTRACT로
        // 승격된다(REQ-F012-001). 리터럴이 없는 응답이면 여전히 SYNTHESIZED.
        assertThat(inventory.responseProvenance())
                .as("소비 리터럴 보유 span 호출 → CONTRACT 값-충실 body(REQ-F012-001)")
                .isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
        assertThat(inventory.responseBody())
                .as("CONTRACT body는 소비 리터럴 'EMBARGOED'를 반영한다")
                .contains("EMBARGOED");
    }
}
