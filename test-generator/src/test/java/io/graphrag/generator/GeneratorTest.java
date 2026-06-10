package io.graphrag.generator;

import io.graphrag.model.AuthMode;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {

    private static final Path GRAPH = Path.of("src/test/resources/fixture-graph");
    private static final GenerationRequest REQUEST = new GenerationRequest(
            "post-api-orders", "post-api-orders-happy",
            "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);

    @Test
    void generate_matchesGoldenFile() throws Exception {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);

        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).relativePath())
                .isEqualTo("io/graphrag/generated/OrdersPostTest.java");

        String expected = Files.readString(
                Path.of("src/test/resources/golden/OrdersPostTest.java.golden"));
        assertThat(result.files().get(0).content()).isEqualTo(expected);
    }

    @Test
    void generate_isDeterministic() {
        GenerationResult first = new Generator(GRAPH).generate(REQUEST);
        GenerationResult second = new Generator(GRAPH).generate(REQUEST);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void generate_withoutPathId_emitsOneClassPerPath() {
        GenerationRequest all = new GenerationRequest(
                "post-api-orders", null, "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(all);

        // fixture 그래프의 post-api-orders path 2개 (happy + 404)
        assertThat(result.files()).extracting(f -> f.relativePath()).containsExactly(
                "io/graphrag/generated/OrdersPostTest_HAPPY.java",
                "io/graphrag/generated/OrdersPostTest_S404_1.java");

        String notFoundTest = result.files().get(1).content();
        // 404 path: 사전 INSERT 없음 + 치환 변수는 사용 + 404 단언
        assertThat(notFoundTest).doesNotContain("INSERT INTO");
        assertThat(notFoundTest).contains("userId = scope.testId() + \"-user\";");
        assertThat(notFoundTest).contains(".statusCode(404)");

        assertThat(result.parallelSafety().fullyParallel())
                .containsExactly("OrdersPostTest_HAPPY", "OrdersPostTest_S404_1");
    }

    @Test
    void generate_mybatisSearchPath_compilesFixtureWithFkParent() {
        GenerationRequest search = new GenerationRequest(
                "post-api-orders-search", null, "OrdersSearchTest", "io.graphrag.generated",
                AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(search);

        assertThat(result.files()).hasSize(1);
        String content = result.files().get(0).content();
        assertThat(content).contains("INSERT INTO users (id, name) VALUES (?, ?)");
        assertThat(content).contains("INSERT INTO orders (user_id, amount, type, status)");
        assertThat(content).contains(".post(\"/api/orders/search\")");
    }

    @Test
    void generate_reportsParallelSafety() {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);
        assertThat(result.parallelSafety().fullyParallel()).containsExactly("OrdersPostTest");
        assertThat(result.parallelSafety().serialRequired()).isEmpty();
    }
}
