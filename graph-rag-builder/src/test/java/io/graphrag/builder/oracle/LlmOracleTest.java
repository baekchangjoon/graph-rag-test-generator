package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.index.SourceRoots;
import io.graphrag.builder.index.ValidationConstraintExtractor;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.CtModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LlmOracleTest {
    private static final String DTO = "io.graphrag.sample.validation.ValidatedRequest";
    private static final String CTRL = "io.graphrag.sample.validation.ValidatedController";
    private static final InputOracle.SutCode SUT =
            new InputOracle.SutCode(Path.of("src/test/resources/sample-src"), null);

    private IndexResult index() {
        var shape = new BodyShape(DTO, List.of(
                new BodyShape.BodyField("name", "java.lang.String"),
                new BodyShape.BodyField("quantity", "java.lang.Integer"),
                new BodyShape.BodyField("price", "java.lang.Integer"),
                new BodyShape.BodyField("contact", "java.lang.String"),
                new BodyShape.BodyField("code", "java.lang.String")), false);
        var ep = new Endpoint("post-validated", "POST", "/validated", CTRL, "create",
                List.of(new EndpointParam("req", DTO, ParamKind.BODY)), false);
        return new IndexResult(List.of(ep), Map.of(DTO, shape), Set.of("post-validated"));
    }

    private LlmOracle oracle(LlmValueClient client, LlmValueCache cache, boolean usable) {
        return new LlmOracle(index(), new ValidationConstraintExtractor(),
                new HandlerSourceExtractor(SUT.srcDir()), client, cache,
                "claude-haiku-4-5-20251001", usable);
    }

    @Test
    void implementsSpiAndContributesStringsOnly(@TempDir Path dir) {  // REQ-001
        var oracle = oracle(FakeValueClient.of("code", "ABC"), new LlmValueCache(dir), true);
        assertThat(oracle.name()).isEqualTo("llm");
        InputCandidates out = oracle.analyze(SUT);
        assertThat(out.strings()).containsKey("code");
        assertThat(out.strings().get("code")).contains("ABC");
        assertThat(out.numeric()).isEmpty();
        assertThat(out.tuples()).isEmpty();
        assertThat(out.reals()).isEmpty();
        assertThat(out.realTuples()).isEmpty();
    }

    @Test
    void deterministicOutputOnSameInput(@TempDir Path dir) {  // REQ-002
        var oracle = oracle(FakeValueClient.of("code", "ABC"), new LlmValueCache(dir), true);
        assertThat(oracle.analyze(SUT).strings()).isEqualTo(oracle.analyze(SUT).strings());
    }

    @Test
    void cacheHitSkipsClientCall(@TempDir Path dir) {  // REQ-002
        var fake = FakeValueClient.of("code", "ABC");
        var oracle = oracle(fake, new LlmValueCache(dir), true);
        oracle.analyze(SUT);   // miss → generate(1) → write
        oracle.analyze(SUT);   // hit → no generate
        assertThat(fake.calls).isEqualTo(1);
    }

    @Test
    void noKeyCacheMissSkips(@TempDir Path dir) {  // REQ-005
        var fake = FakeValueClient.of("code", "ABC");
        var oracle = oracle(fake, new LlmValueCache(dir), false);   // clientUsable=false
        InputCandidates out = oracle.analyze(SUT);
        assertThat(out.strings()).isEmpty();
        assertThat(fake.calls).isZero();
    }

    @Test
    void selectsDomainCodeQueryParamOnReadEndpoint(@TempDir Path dir) {  // REQ-019
        // 바디 없는 read 엔드포인트(GET /items?status=...) — PATH/QUERY 파라미터 입력면.
        var ep = new Endpoint("get-items", "GET", "/items", "io.x.ItemController", "list",
                List.of(new EndpointParam("status", "java.lang.String", ParamKind.QUERY),
                        new EndpointParam("page", "int", ParamKind.QUERY)), false);
        var idx = new IndexResult(List.of(ep), Map.of(), Set.of("get-items"));
        var oracle = new LlmOracle(idx, new ValidationConstraintExtractor(),
                new HandlerSourceExtractor(SUT.srcDir()), FakeValueClient.of("status", "ACTIVE"),
                new LlmValueCache(dir), "claude-haiku-4-5-20251001", true);
        InputCandidates out = oracle.analyze(SUT);
        assertThat(out.strings()).containsKey("status");          // 도메인코드 쿼리 파라미터 선별+기여
        assertThat(out.strings().get("status")).contains("ACTIVE");
    }

    @Test
    void reusesInjectedSharedModelWithoutExtraBuild(@TempDir Path dir) {  // R5
        SharedSpoonModel.resetBuildCount();
        CtModel model = SharedSpoonModel.build(SourceRoots.single(SUT.srcDir()));   // 유일한 빌드
        var oracle = new LlmOracle(index(), new ValidationConstraintExtractor(),
                new HandlerSourceExtractor(model), FakeValueClient.of("code", "ABC"),
                new LlmValueCache(dir), "claude-haiku-4-5-20251001", true, model);
        oracle.analyze(SUT);
        assertThat(SharedSpoonModel.buildCount())
                .as("주입된 공유 모델 재사용 — validation/handlerSrc 추출이 엔드포인트마다 재빌드하지 않음")
                .isEqualTo(1);
    }

    @Test
    void clientFailureSkipsEndpointOnly(@TempDir Path dir) {  // REQ-015
        var failing = new FakeValueClient(LlmFieldValues.empty(), true);
        var oracle = oracle(failing, new LlmValueCache(dir), true);
        assertThatCode(() -> {
            InputCandidates out = oracle.analyze(SUT);
            assertThat(out.strings()).isEmpty();
        }).doesNotThrowAnyException();
    }
}
