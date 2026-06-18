package io.graphrag.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.generator.compose.FixtureComposer;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GeneratorCollectionBodyTest {
    @Test void arraySampleInput_emittedAsArrayBody() throws Exception {
        JsonNode arr = Json.mapper().readTree("[{\"name\":\"sample-name\",\"amount\":1}]");
        String body = FixtureComposer.bodyFormatFor(arr);
        assertThat(body).startsWith("[").contains("\"name\"");
    }
    @Test void objectSampleInput_emittedAsObjectTemplate() throws Exception {
        JsonNode o = Json.mapper().readTree("{\"name\":\"x\"}");
        assertThat(FixtureComposer.bodyFormatFor(o)).startsWith("{");
    }
}
