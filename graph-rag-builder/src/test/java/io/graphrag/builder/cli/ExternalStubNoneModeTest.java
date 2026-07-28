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
 * REQ-014: {@code --trace-mode none}에서 trace-id 없이 count-delta 직렬 귀속으로 외부 stub 합성·통과가
 * 동작한다. 1차 invoke 직후 drain 결과 전체를 그 요청의 unmatched 외부 호출로 귀속해 합성·등록한다.
 * Docker 필요.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class ExternalStubNoneModeTest {

    @TempDir
    Path out;

    @Test
    @DisplayName("REQ-014: none 모드 직렬 폴백 — 외부 stub 합성·통과")
    void noneMode_synthesizesAndPassesSerially() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        Path emptyStubs = Files.createTempDirectory("none-mode-no-stubs");   // 외부 stub 없음

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, out,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, emptyStubs,
                Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}"),
                null, null, authConfig, false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "none", null, false));

        // egress-assertion 산출물(pathId 접미 -egressassert)은 같은 urlPath를 갖는 계약 산출물이라
        // 제외한다 — 없으면 findFirst()가 리스트 순서에 따라 그쪽을 집을 수 있다.
        CapturedHttpCall inventory = asset.httpCalls().stream()
                .filter(c -> c.urlPath().equals("/inventory/stock"))
                .filter(c -> !c.pathId().endsWith("-egressassert"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "none 모드에서 외부 inventory 호출이 캡처되지 않음"));
        assertThat(inventory.responseStatus())
                .as("none 모드 합성 stub 200").isEqualTo(200);
        assertThat(inventory.responseProvenance())
                .as("none 모드 합성 경유 SYNTHESIZED")
                .isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);

        // 외부 직후 분기(available < amount → 409)가 none 모드에서도 열린다.
        List<Integer> externalStatuses = asset.paths().stream()
                .filter(p -> p.endpointId().equals("post-api-orders"))
                .filter(p -> !p.capturedHttpCallIds().isEmpty())
                .map(ExploredPath::expectedStatus)
                .toList();
        assertThat(externalStatuses).as("none 모드 외부 직후 409 분기 도달").contains(409);
    }
}
