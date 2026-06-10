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
    void generate_reportsParallelSafety() {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);
        assertThat(result.parallelSafety().fullyParallel()).containsExactly("OrdersPostTest");
        assertThat(result.parallelSafety().serialRequired()).isEmpty();
    }
}
