package io.graphrag.builder.capture;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-013: otel redirect-capable 경로에서 값-충실 body(CONTRACT) 합성 + 생성 단언 (red).
 *
 * <p>order-service를 {@code EXTERNAL_INVENTORY_URL={{wiremock}}}·{@code --trace-mode otel}로 빌드한다.
 * WireMock redirect(recorder)를 경유해 외부 호출을 캡처하므로 {@code stubSynthesizer.isRegistered==true}
 * → 변형 루프가 구동된다.
 *
 * <p>이어 {@code Generator}를 실행해 생성 소스 텍스트에 단언한다:
 * <ul>
 *   <li>graph {@code httpCalls}의 {@code responseProvenance=="CONTRACT"} &amp; body에 {@code "EMBARGOED"}/{@code "BACKORDER"} 포함.</li>
 *   <li>생성 소스 합본에 happy(201)·region="EMBARGOED"(422)·mode="BACKORDER"(409) 분기별 단언 존재.</li>
 * </ul>
 *
 * <p>구현(Task 2~8) 전까지 RED가 정상이며 약화 금지.
 *
 * <p>필요 조건: {@code -Dsut.jar=...} {@code -Dsut.src=...} 둘 다 지정. 미충족 시 skip.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@EnabledIfSystemProperty(named = "sut.src", matches = ".+")
class EgressStubBodyFidelityOtelE2E {

    private static final String ORDERS_ENDPOINT = "post-api-orders";

    @TempDir
    Path out;

    @Test
    @DisplayName("REQ-F012-013: otel redirect-capable 값-충실 변형 단언")
    void otelRedirectCapableValueFaithfulVariantAssertion() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        Path buildOut = Files.createTempDirectory(out, "build");
        // external stubs 없음 → shape-seeded stub 합성 + redirect 경로 구동
        Path noExternalStubs = Files.createTempDirectory(out, "no-stubs");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        // otel redirect: EXTERNAL_INVENTORY_URL={{wiremock}} → recorder redirect 경유 → 변형 루프 구동
        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, buildOut,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, noExternalStubs,
                Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}"),
                null, null, authConfig, false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "otel", null, false));

        // ── 단언 1: graph httpCalls에 CONTRACT provenance + 기대값 포함 ──────────────────
        // CONTRACT provenance를 가진 inventory 호출이 하나 이상 존재해야 한다.
        List<CapturedHttpCall> contractCalls = asset.httpCalls().stream()
                .filter(c -> c.urlPath().contains("/inventory/stock"))
                .filter(c -> c.responseProvenance() == CapturedHttpCall.Provenance.CONTRACT)
                .toList();
        assertThat(contractCalls)
                .as("graph httpCalls에 responseProvenance==CONTRACT인 /inventory/stock 호출이 있어야 한다")
                .isNotEmpty();

        // CONTRACT body에 기대값 "EMBARGOED" 또는 "BACKORDER"가 포함돼야 한다.
        boolean embargoedInBody = contractCalls.stream()
                .anyMatch(c -> c.responseBody() != null && c.responseBody().contains("EMBARGOED"));
        boolean backorderInBody = contractCalls.stream()
                .anyMatch(c -> c.responseBody() != null && c.responseBody().contains("BACKORDER"));
        assertThat(embargoedInBody)
                .as("CONTRACT body에 'EMBARGOED' 기대값이 포함돼야 한다(placeholder 'sample-region' 아님)")
                .isTrue();
        assertThat(backorderInBody)
                .as("CONTRACT body에 'BACKORDER' 기대값이 포함돼야 한다")
                .isTrue();

        // "sample-region" placeholder는 CONTRACT body에 없어야 한다.
        boolean hasPlaceholder = contractCalls.stream()
                .anyMatch(c -> c.responseBody() != null && c.responseBody().contains("sample-region"));
        assertThat(hasPlaceholder)
                .as("CONTRACT body에 형상-시드 placeholder 'sample-region'이 없어야 한다")
                .isFalse();

        // ── 단언 2: Generator 실행 → 생성 소스에 분기별 단언 존재 ───────────────────────
        GenerationRequest genReq = new GenerationRequest(
                ORDERS_ENDPOINT, null, "OrdersCreateEgressTest", "io.x", AuthMode.REAL);
        GenerationResult genResult = new Generator(buildOut).generate(genReq);

        String allSource = genResult.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(io.graphrag.model.GeneratedFile::content)
                .collect(Collectors.joining("\n"));

        assertThat(genResult.files())
                .as("Generator가 생성 파일을 산출해야 한다")
                .isNotEmpty();

        // happy(201): 201 상태 코드 단언이 생성 소스에 존재해야 한다.
        assertThat(allSource)
                .as("생성 소스에 201 상태 단언(happy path)이 존재해야 한다")
                .contains("201");

        // region="EMBARGOED"(422): 422 단언 + EMBARGOED 문자열이 생성 소스에 존재해야 한다.
        assertThat(allSource)
                .as("생성 소스에 422 상태 단언(EMBARGOED arm)이 존재해야 한다")
                .contains("422");
        assertThat(allSource)
                .as("생성 소스에 'EMBARGOED' 기대값 stub body가 포함돼야 한다")
                .contains("EMBARGOED");

        // mode="BACKORDER"(409): 409 단언이 생성 소스에 존재해야 한다.
        assertThat(allSource)
                .as("생성 소스에 409 상태 단언(BACKORDER arm)이 존재해야 한다")
                .contains("409");
        assertThat(allSource)
                .as("생성 소스에 'BACKORDER' 기대값 stub body가 포함돼야 한다")
                .contains("BACKORDER");

        // egress-assertion discoveredBy path가 생성됐음을 확인(generator가 포함해야 함).
        assertThat(asset.paths().stream()
                .anyMatch(p -> "egress-assertion".equals(p.discoveredBy())))
                .as("graph paths에 discoveredBy='egress-assertion' 경로가 존재해야 한다")
                .isTrue();
    }
}
