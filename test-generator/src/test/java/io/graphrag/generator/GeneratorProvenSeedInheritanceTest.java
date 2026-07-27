package io.graphrag.generator;

import io.graphrag.model.AuthMode;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N2 회귀(리뷰 Important): REQ-037의 "증명된 형제 키 값 상속"이 REQ-005("200-엔벨로프 실패 path에는
 * spurious seed가 없다")를 깨지 않음을 <b>Generator 레벨</b>에서 고정한다.
 *
 * <p>기존 단위 테스트({@code LookupSucceededOutcomeTest})는 {@code FixtureComposer}를 빈 증명 집합으로
 * 직접 호출해 이 상호작용을 전혀 커버하지 못했다 — 증명 집합을 실제로 채우는 것은 {@code Generator}이고,
 * 같은 endpoint에 SUCCESS와 200-엔벨로프 FAILURE가 공존할 때만 회귀가 드러난다.
 *
 * <p>고치기 전의 결함 두 가지:
 * <ul>
 *   <li>상속 매칭이 <b>값 문자열 only</b>였다 — 테이블·컬럼이 달라도 값만 같으면 시드가 붙었다
 *       (예: {@code users.id}가 증명됐는데 {@code orders.user_id} 조회에도 시드가 붙음).</li>
 *   <li>2xx 엔벨로프 실패 path도 상속 대상이었다 — 같은 (table, column, value)를 조회하므로 매칭을
 *       좁혀도 걸리며, REQ-005가 금지한 spurious seed가 그대로 생겼다.</li>
 * </ul>
 */
class GeneratorProvenSeedInheritanceTest {

    private static final Path GRAPH = Path.of("src/test/resources/fixture-req005-graph");

    private static String javaFor(String pathId, String className) {
        GenerationResult result = new Generator(GRAPH).generate(new GenerationRequest(
                "post-api-orders", pathId, className, "io.graphrag.generated", AuthMode.DISABLED));
        return result.files().stream()
                .filter(f -> f.relativePath().endsWith(className + ".java"))
                .map(GeneratedFile::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no generated class for pathId=" + pathId));
    }

    @Test
    @DisplayName("REQ-005: 같은 endpoint에 SUCCESS 형제가 있어도 200-엔벨로프 FAILURE path에는 seed INSERT가 없다")
    void envelopeFailurePathGetsNoSeedEvenWhenSuccessSiblingProvesTheSameKey() {
        String envelope = javaFor("post-api-orders-env200", "OrdersEnvelopeTest");

        assertThat(envelope)
                .as("REQ-005: 200-엔벨로프 실패 path에 spurious seed INSERT가 생기면 안 된다")
                .doesNotContain("INSERT INTO users");
        assertThat(envelope)
                .as("REQ-005: 어떤 테이블로든 사전 INSERT가 생기면 안 된다")
                .doesNotContain("INSERT INTO");
    }

    @Test
    @DisplayName("REQ-037(회귀 0): non-2xx 파생 실패 path는 증명된 형제 키 값의 시드를 그대로 상속한다")
    void derivedNon2xxFailurePathStillInheritsProvenSeed() {
        String derived = javaFor("post-api-orders-derived422", "OrdersDerivedTest");

        assertThat(derived)
                .as("REQ-037: 2xx 형제가 users.id로 조회에 성공했으므로 422 파생 path는 시드를 상속해야 한다")
                .contains("INSERT INTO users");
    }

    @Test
    @DisplayName("N2: 증명은 (table, column, value) 조합으로만 성립한다 — 값만 같은 다른 컬럼 조회는 상속하지 않는다")
    void provenKeyDoesNotLeakAcrossTableAndColumn() {
        String cross = javaFor("post-api-orders-cross409", "OrdersCrossTest");

        assertThat(cross)
                .as("users.id만 증명됐는데 orders.user_id 조회에 시드가 붙으면 값-only 매칭 회귀다")
                .doesNotContain("INSERT INTO");
    }

    @Test
    @DisplayName("REQ-037(회귀 0): 증명의 출처인 SUCCESS path 자체는 기존대로 시드를 만든다")
    void successPathStillSeeds() {
        String success = javaFor("post-api-orders-suc200", "OrdersSuccessTest");

        assertThat(success).contains("INSERT INTO users");
    }
}
