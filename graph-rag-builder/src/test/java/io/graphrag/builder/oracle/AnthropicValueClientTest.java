package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AnthropicValueClientTest {
    @Test
    void constructsWithoutApiKey() {  // REQ-005
        assertThatCode(() -> AnthropicValueClient.fromEnv("claude-haiku-4-5-20251001"))
                .doesNotThrowAnyException();
    }

    @Test
    void modelIdPinned() {  // REQ-013
        assertThat(AnthropicValueClient.fromEnv("claude-sonnet-4-6").modelId())
                .isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void prepareUsesTemperature0ModelPinAndStructuredPrompt() {  // REQ-013
        var req = new LlmRequest("post-x", "if (code.startsWith(\"GOLD\")) {}",
                List.of(new BodyShape.BodyField("code", "java.lang.String")),
                Map.of("code", "[A-Z]{4}-\\d{4}"), Set.of(), "claude-haiku-4-5-20251001");
        var call = AnthropicValueClient.prepare(req);
        assertThat(call.temperature()).isZero();
        assertThat(call.model()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(call.userPrompt()).contains("code").contains("[A-Z]{4}-\\d{4}").contains("startsWith");
        assertThat(call.systemPrompt()).contains("JSON");
    }

    @Test
    void parsesExpectedJsonShape() {  // REQ-013
        var vals = AnthropicValueClient.parse(
                "{\"fields\":[{\"field\":\"code\",\"values\":[\"GOLD-1234\"]}]}");
        assertThat(vals.stringValuesByField()).containsEntry("code", List.of("GOLD-1234"));
    }

    @Test
    void bedrockFactoryPrefixesModelLazily() {  // REQ-018
        assertThatCode(() -> {
            var c = AnthropicValueClient.bedrock("claude-haiku-4-5-20251001");
            assertThat(c.modelId()).isEqualTo("anthropic.claude-haiku-4-5-20251001");
        }).doesNotThrowAnyException();   // 자격증명 없어도 생성 실패 금지(lazy)
    }

    @Test
    void bedrockFactoryDoesNotDoublePrefix() {  // REQ-018
        assertThat(AnthropicValueClient.bedrock("anthropic.claude-haiku-4-5").modelId())
                .isEqualTo("anthropic.claude-haiku-4-5");
    }
}
