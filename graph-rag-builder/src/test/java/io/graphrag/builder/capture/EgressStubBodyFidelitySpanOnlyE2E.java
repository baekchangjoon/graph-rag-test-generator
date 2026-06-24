package io.graphrag.builder.capture;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.cli.BuildConfig;
import io.graphrag.builder.cli.BuilderCli;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.generator.Generator;
import io.graphrag.model.AuthMode;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-014: span-only 경로(recorder redirect 미사용)에서 CONTRACT body 합성 + 미구동 loud 단언 (red).
 *
 * <p>order-service를 {@code EXTERNAL_INVENTORY_URL}=직접 host stub URL·{@code --trace-mode otel}로 빌드한다.
 * recorder(WireMock) redirect를 사용하지 않으므로 {@code stubSynthesizer.isRegistered==false} →
 * 변형 루프가 구동되지 않는다.
 *
 * <p>단언:
 * <ul>
 *   <li>발견 호출의 graph {@code responseProvenance=="CONTRACT"} &amp; body에 "EMBARGOED" 반영.</li>
 *   <li>생성 소스에 422/409 외부-의존 단언 테스트 메서드 부재(SUT status 단언 없음 — 변형 미구동).</li>
 *   <li>{@code exploration-report.json}의 {@code unsupportedShapes[].reason}에 {@code egress-branch-undriven} 존재.</li>
 * </ul>
 *
 * <p>구현(Task 2~8) 전까지 RED가 정상이며 약화 금지.
 *
 * <p>필요 조건: {@code -Dsut.jar=...} {@code -Dsut.src=...} 둘 다 지정. 미충족 시 skip.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@EnabledIfSystemProperty(named = "sut.src", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EgressStubBodyFidelitySpanOnlyE2E {

    private static final String INVENTORY_RESPONSE = "{\"available\":5,\"mode\":\"STANDARD\",\"region\":\"OK\"}";
    private static final String ORDERS_ENDPOINT = "post-api-orders";

    @TempDir
    Path out;

    // 이 테스트가 기동한 host stub (REQ-F012-016: teardown 필수)
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
        // 이 테스트가 띄운 host stub만 정리 (REQ-F012-016 teardown).
        // SUT 프로세스·Postgres 컨테이너 수명은 BuilderCli.build 내부(Testcontainers Ryuk)가 관리한다.
        if (inventoryStub != null) {
            inventoryStub.stop(0);
        }
    }

    @Test
    @DisplayName("REQ-F012-014: span-only CONTRACT body + 미구동 loud")
    void spanOnlyContractBodyAndUndrivenLoud() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        Path buildOut = Files.createTempDirectory(out, "build");
        Path noExternalStubs = Files.createTempDirectory(out, "no-stubs");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        // span-only: EXTERNAL_INVENTORY_URL=직접 host stub URL → recorder redirect 미사용
        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, buildOut,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, noExternalStubs,
                Map.of("EXTERNAL_INVENTORY_URL", inventoryUrl),   // 직접 URL — redirect 없음
                null, null, authConfig, false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "otel", null, false));

        // ── 단언 1: graph httpCalls에 CONTRACT provenance + "EMBARGOED" 반영 ───────────
        List<CapturedHttpCall> inventoryCalls = asset.httpCalls().stream()
                .filter(c -> c.urlPath().contains("/inventory/stock"))
                .toList();
        assertThat(inventoryCalls)
                .as("/inventory/stock httpCalls가 그래프에 기록돼야 한다")
                .isNotEmpty();

        // CONTRACT provenance: 기대값 합성이 적용됐어야 한다.
        List<CapturedHttpCall> contractCalls = inventoryCalls.stream()
                .filter(c -> c.responseProvenance() == CapturedHttpCall.Provenance.CONTRACT)
                .toList();
        assertThat(contractCalls)
                .as("span-only 발견 호출의 responseProvenance가 CONTRACT여야 한다")
                .isNotEmpty();

        // CONTRACT body에 "EMBARGOED" 기대값이 포함돼야 한다.
        boolean hasEmbargoed = contractCalls.stream()
                .anyMatch(c -> c.responseBody() != null && c.responseBody().contains("EMBARGOED"));
        assertThat(hasEmbargoed)
                .as("CONTRACT body에 'EMBARGOED' 기대값이 반영돼야 한다")
                .isTrue();

        // ── 단언 2: 생성 소스에 외부-응답 분기(422/409) SUT status 단언 메서드 부재 ─────
        // span-only는 변형 루프가 구동되지 않으므로 egress-assertion path가 없어야 한다.
        GenerationRequest genReq = new GenerationRequest(
                ORDERS_ENDPOINT, null, "OrdersSpanOnlyTest", "io.x", AuthMode.REAL);
        GenerationResult genResult = new Generator(buildOut).generate(genReq);

        String allSource = genResult.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(io.graphrag.model.GeneratedFile::content)
                .collect(Collectors.joining("\n"));

        // 외부 응답 의존 분기(422, 409)에 대한 SUT status 단언 메서드가 생성 소스에 없어야 한다.
        // egress-assertion path가 없으므로 이 값들이 단언 메서드로 표현되면 안 된다.
        assertThat(asset.paths().stream()
                .anyMatch(p -> "egress-assertion".equals(p.discoveredBy())))
                .as("span-only 모드에서 egress-assertion discoveredBy path가 없어야 한다(변형 루프 미구동)")
                .isFalse();

        // ── 단언 3: exploration-report.json에 egress-branch-undriven loud 존재 ──────────
        Path reportPath = buildOut.resolve("exploration-report.json");
        assertThat(reportPath)
                .as("exploration-report.json이 buildOut에 생성돼야 한다")
                .exists();

        String reportJson = Files.readString(reportPath);
        com.fasterxml.jackson.databind.JsonNode report = Json.mapper().readTree(reportJson);

        com.fasterxml.jackson.databind.JsonNode shapes = report.get("unsupportedShapes");
        assertThat(shapes)
                .as("exploration-report.json에 unsupportedShapes 배열이 있어야 한다")
                .isNotNull();

        boolean hasUndrivenLoud = false;
        if (shapes.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode shape : shapes) {
                com.fasterxml.jackson.databind.JsonNode reason = shape.get("reason");
                if (reason != null && "egress-branch-undriven".equals(reason.asText())) {
                    hasUndrivenLoud = true;
                    break;
                }
            }
        }
        assertThat(hasUndrivenLoud)
                .as("exploration-report.json의 unsupportedShapes에 reason='egress-branch-undriven'이 있어야 한다")
                .isTrue();
    }
}
