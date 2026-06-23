package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmBackendSelectorTest {
    @Test
    void selectsByName() {  // REQ-016
        assertThat(LlmBackends.create("api", "m", "claude").client())
                .isInstanceOf(AnthropicValueClient.class);
        assertThat(LlmBackends.create("bedrock", "m", "claude").client())
                .isInstanceOf(AnthropicValueClient.class);
        assertThat(LlmBackends.create("cli", "m", "claude").client())
                .isInstanceOf(CliValueClient.class);
    }

    @Test
    void bedrockAndCliAreUsableApiDependsOnKey() {  // REQ-016
        assertThat(LlmBackends.create("bedrock", "m", "claude").usable()).isTrue();
        assertThat(LlmBackends.create("cli", "m", "claude").usable()).isTrue();
        // api usable == hasApiKey() — 환경 의존이므로 값 자체는 단언하지 않고 호출만 검증.
    }

    @Test
    void unknownBackendThrows() {  // REQ-016
        assertThatThrownBy(() -> LlmBackends.create("gemini", "m", "claude"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api|bedrock|cli");
    }
}
