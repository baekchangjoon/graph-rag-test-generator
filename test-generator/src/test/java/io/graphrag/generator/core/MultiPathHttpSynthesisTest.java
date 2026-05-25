package io.graphrag.generator.core;

import io.graphrag.generator.verify.CompileResult;
import io.graphrag.generator.verify.JavaSourceCompiler;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpClientType;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 합성기 통합: TestSynthesizer.synthesizeMulti가 CapturedHttpCall도 처리.
 */
class MultiPathHttpSynthesisTest {

    private final Endpoint orderPost = new Endpoint(
            "POST:/api/orders/with-inventory", HttpMethod.POST, "/api/orders/with-inventory",
            "demo-sut", "OrdersController", "createWithInventory", false, List.of());

    private ExploredPath path(String id, int status) {
        return new ExploredPath(id, orderPost.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(),
                        Map.of("userId", "u-1", "amount", 100, "type", "EXPRESS")),
                null, List.of(), status, null, "cov-" + id, "v1");
    }

    private CapturedHttpCall inventoryGet(String pathId, int respStatus, String body) {
        return new CapturedHttpCall(
                "h-" + pathId, pathId, "GET",
                "/inventory/stock?type=EXPRESS", "/inventory/stock?type=EXPRESS",
                List.of(), Map.of(), null, List.of(),
                respStatus, body, List.of("available"),
                HttpClientType.OTHER, "inventory");
    }

    @Test
    void httpStubFragmentEmbeddedInTestMethod() {
        PathContext pc = new PathContext(
                path("p1", 201),
                List.of(),
                List.of(inventoryGet("p1", 200, "{\"available\":50}")));

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(orderPost, List.of(pc), "gen"));

        assertThat(code)
                .contains("import com.github.tomakehurst.wiremock.client.WireMock;")
                .contains("import static com.github.tomakehurst.wiremock.client.WireMock.*;")
                .contains("WireMock.configureFor(")
                .contains("WireMock.reset()")
                .contains("stubFor(")
                .contains("get(urlPathEqualTo(\"/inventory/stock\"))")
                .contains("withQueryParam(\"type\", equalTo(\"EXPRESS\"))")
                .contains("withStatus(200)");
    }

    @Test
    void pathWithoutHttpDoesNotImportWireMock() {
        PathContext pc = new PathContext(path("p1", 400), List.of(), List.of());

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(orderPost, List.of(pc), "gen"));

        assertThat(code)
                .doesNotContain("import com.github.tomakehurst.wiremock")
                .doesNotContain("stubFor(");
    }

    @Test
    void mixedPathsOnlyAddStubInPathsThatHaveCapture() {
        PathContext withHttp = new PathContext(
                path("happy", 201), List.of(),
                List.of(inventoryGet("happy", 200, "{\"available\":50}")));
        PathContext bareValidation = new PathContext(path("badAmount", 400), List.of(), List.of());

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(orderPost, List.of(withHttp, bareValidation), "gen"));

        // happy path 메소드 안에는 stubFor 등장
        int happyIdx = code.indexOf("void path_happy(");
        int badAmtIdx = code.indexOf("void path_badAmount(");
        int firstStubFor = code.indexOf("stubFor(");

        assertThat(happyIdx).isGreaterThan(0);
        assertThat(badAmtIdx).isGreaterThan(happyIdx);
        assertThat(firstStubFor).isGreaterThan(happyIdx)
                .isLessThan(badAmtIdx);
        // badAmount path 다음에는 새 stubFor 등장 안 함 (해당 path에 HTTP 없음)
        int nextStubAfterBad = code.indexOf("stubFor(", badAmtIdx);
        assertThat(nextStubAfterBad).isEqualTo(-1);
    }

    @Test
    void synthesizedHttpClassCompiles() {
        PathContext pc = new PathContext(
                path("p1", 201), List.of(),
                List.of(inventoryGet("p1", 200, "{\"available\":50}")));

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(orderPost, List.of(pc), "gen"));

        CompileResult r = JavaSourceCompiler.compile("gen.WithInventoryPostTest", code);
        assertThat(r.success())
                .as("WireMock-integrated synth should compile. diagnostics=" + r.diagnostics())
                .isTrue();
    }

    @Test
    void deterministicWithHttp() {
        PathContext pc = new PathContext(
                path("p1", 201), List.of(),
                List.of(inventoryGet("p1", 200, "{\"available\":50}")));
        MultiPathSynthesisInput input =
                new MultiPathSynthesisInput(orderPost, List.of(pc), "gen");

        assertThat(TestSynthesizer.synthesizeMulti(input))
                .isEqualTo(TestSynthesizer.synthesizeMulti(input));
    }
}
