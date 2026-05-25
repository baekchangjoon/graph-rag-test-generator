package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathExplorerKindTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void containsAllEngines() {
        assertThat(PathExplorerKind.values())
                .containsExactly(
                        PathExplorerKind.JDART,
                        PathExplorerKind.FUZZER,
                        PathExplorerKind.EVOSUITE);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        for (PathExplorerKind v : PathExplorerKind.values()) {
            String json = mapper.writeValueAsString(v);
            assertThat(mapper.readValue(json, PathExplorerKind.class)).isEqualTo(v);
        }
    }
}
