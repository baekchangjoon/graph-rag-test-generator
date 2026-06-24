package io.graphrag.builder.capture;

import io.graphrag.builder.cli.BuildConfig;
import io.graphrag.builder.cli.BuilderCli;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.oracle.ClassifierConfig;
import io.graphrag.generator.Generator;
import io.graphrag.model.AuthMode;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
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
 * REQ-F012-018: envelope 티어 E2E — error-envelope-service 실 SUT에서 egress envelope 분기 구동 실증.
 *
 * <p>error-envelope-service를 {@code EXTERNAL_PRICING_URL={{wiremock}}}·
 * {@code --error-when-present errorCode}·{@code --error-detail-field errorDetail}·
 * {@code --trace-mode otel}로 빌드한다.
 *
 * <p>WireMock redirect(recorder)를 경유하므로 {@code mergeEnvelopeCandidates}가 errorCode 필드에
 * "ERROR" 후보를 주입해 변형 루프를 구동한다.
 * → pricing egress 호출의 {@code responseProvenance==CONTRACT} + body에 {@code "ERROR"} 포함.
 * → egress-assertion path가 그래프에 존재한다.
 * → 모든 CONTRACT httpCall id가 어떤 path의 capturedHttpCallIds에 참조된다(dead data 없음).
 *
 * <p>필요 조건: {@code -Dsut.jar=...} {@code -Dsut.src=...} 둘 다 지정. 미충족 시 skip.
 * (실제 사용하는 jar/src 경로는 이 테스트가 직접 error-envelope-service로 지정한다.
 *  두 시스템 프로퍼티는 integration 환경 가용 여부 게이트로만 쓴다.)
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@EnabledIfSystemProperty(named = "sut.src", matches = ".+")
class EgressStubBodyFidelityEnvelopeE2E {

    private static final String PRICE_ENDPOINT = "get-items-id-price";
    private static final String PRICING_PATH = "/pricing/quote";

    @TempDir
    Path out;

    @Test
    @DisplayName("REQ-F012-018: envelope egress 티어 실 SUT 실증")
    void envelopeEgressTierRealSutProven() throws Exception {
        // error-envelope-service 경로를 sut.jar 앵커에서 역방향으로 파생한다.
        // sut.jar = <root>/samples/order-service/build/libs/order-service.jar
        // error-envelope src = <root>/samples/error-envelope-service/src/main/java
        // error-envelope jar = <root>/samples/error-envelope-service/build/libs/error-envelope-service.jar
        Path orderJar = Path.of(System.getProperty("sut.jar"));
        Path samplesDir = orderJar.getParent()     // libs/
                .getParent()                       // build/
                .getParent()                       // order-service/
                .getParent();                      // samples/

        Path envelopeSrc = samplesDir.resolve("error-envelope-service/src/main/java");
        Path envelopeJar = samplesDir.resolve(
                "error-envelope-service/build/libs/error-envelope-service.jar");
        Path envelopeResources = envelopeSrc.resolveSibling("resources");

        assertThat(envelopeSrc).as("error-envelope-service src 디렉터리가 존재해야 한다").isDirectory();
        assertThat(envelopeJar).as("error-envelope-service.jar가 빌드돼 있어야 한다").exists();

        Path buildOut = Files.createTempDirectory(out, "build");
        // external stubs 없음 → {{wiremock}} WireMock recorder redirect 경유로 변형 루프 구동
        Path noExternalStubs = Files.createTempDirectory(out, "no-stubs");

        // --error-when-present errorCode --error-detail-field errorDetail
        ClassifierConfig classifierConfig = ClassifierConfig.from(Map.of(
                "--error-when-present", "errorCode",
                "--error-detail-field", "errorDetail"));

        // error-envelope-service는 인증 없음 — authConfig null
        GraphAsset asset = BuilderCli.build(new BuildConfig(
                envelopeSrc, envelopeResources, envelopeJar, buildOut,
                "error-envelope-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, noExternalStubs,
                // EXTERNAL_PRICING_URL={{wiremock}} → WireMock recorder redirect 경유
                Map.of("EXTERNAL_PRICING_URL", "{{wiremock}}"),
                null, null,
                null,  // authConfig: 인증 없음
                false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "otel", classifierConfig, false));

        // ── 단언 1: graph httpCalls에 CONTRACT provenance + errorCode="ERROR" 포함 ──────
        List<CapturedHttpCall> contractCalls = asset.httpCalls().stream()
                .filter(c -> c.urlPath() != null && c.urlPath().contains(PRICING_PATH))
                .filter(c -> c.responseProvenance() == CapturedHttpCall.Provenance.CONTRACT)
                .toList();

        assertThat(contractCalls)
                .as("pricing egress 호출이 responseProvenance==CONTRACT로 graph에 기록돼야 한다")
                .isNotEmpty();

        // CONTRACT body에 envelope 오류 값("ERROR")이 포함돼야 한다.
        boolean hasErrorValue = contractCalls.stream()
                .anyMatch(c -> c.responseBody() != null && c.responseBody().contains("ERROR"));
        assertThat(hasErrorValue)
                .as("CONTRACT body에 envelope 오류 값('ERROR')이 포함돼야 한다(errorCode 필드 주입)")
                .isTrue();

        // ── 단언 2: egress-assertion path가 존재한다 ──────────────────────────────────────
        boolean hasEgressAssertionPath = asset.paths().stream()
                .anyMatch(p -> "egress-assertion".equals(p.discoveredBy()));
        assertThat(hasEgressAssertionPath)
                .as("graph paths에 discoveredBy='egress-assertion' 경로가 존재해야 한다(envelope 변형 구동 증거)")
                .isTrue();

        // ── 단언 3: 모든 CONTRACT httpCall id가 어느 path의 capturedHttpCallIds에 참조됨 ──
        Set<String> contractCallIds = asset.httpCalls().stream()
                .filter(c -> c.responseProvenance() == CapturedHttpCall.Provenance.CONTRACT)
                .map(CapturedHttpCall::id)
                .collect(Collectors.toSet());

        Set<String> referencedIds = asset.paths().stream()
                .flatMap(p -> p.capturedHttpCallIds().stream())
                .collect(Collectors.toSet());

        Set<String> unreferencedContractIds = contractCallIds.stream()
                .filter(id -> !referencedIds.contains(id))
                .collect(Collectors.toSet());

        assertThat(unreferencedContractIds)
                .as("모든 CONTRACT httpCall id가 어느 path에서 참조돼야 한다(dead data 없음); "
                        + "미참조 id: " + unreferencedContractIds)
                .isEmpty();

        // ── 단언 4: Generator 실행 → 생성 소스에 envelope 단언 존재 ──────────────────────
        GenerationRequest genReq = new GenerationRequest(
                PRICE_ENDPOINT, null, "ItemPriceEnvelopeEgressTest", "io.x", AuthMode.DISABLED);
        GenerationResult genResult = new Generator(buildOut).generate(genReq);

        assertThat(genResult.files())
                .as("Generator가 생성 파일을 산출해야 한다")
                .isNotEmpty();

        String allSource = genResult.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(io.graphrag.model.GeneratedFile::content)
                .collect(Collectors.joining("\n"));

        // SUT가 envelope 분기에서 HTTP 200을 반환하므로 생성된 테스트에 200 단언이 있어야 한다.
        assertThat(allSource)
                .as("생성 소스에 SUT 관측 상태(envelope 분기 HTTP 200) 단언이 존재해야 한다")
                .contains("200");
    }
}
