package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CliValueClientTest {
    @Test
    void buildsPrintCommand() {  // REQ-017 (claude/cursor-agent/agy)
        var cli = CliValueClient.of("claude", "claude-haiku-4-5-20251001");
        var cmd = cli.buildCommand("PROMPT");
        assertThat(cmd).containsExactly("claude", "-p", "--model", "claude-haiku-4-5-20251001", "PROMPT");
    }

    @Test
    void buildsKiroChatCommand() {  // REQ-017 (kiro-cli — 다른 비대화 인터페이스)
        var cli = CliValueClient.of("kiro-cli", "claude-haiku-4.5");
        var cmd = cli.buildCommand("PROMPT");
        assertThat(cmd).containsExactly(
                "kiro-cli", "chat", "--no-interactive", "--model", "claude-haiku-4.5",
                "--trust-tools=", "PROMPT");
    }

    @Test
    void combinedPromptHasSystemAndUserAndConstraints() {  // REQ-017
        var req = new LlmRequest("post-x", "if (code.startsWith(\"GOLD\")) {}",
                List.of(new BodyShape.BodyField("code", "java.lang.String")),
                Map.of("code", "[A-Z]{4}-\\d{4}"), Set.of(), "claude-haiku-4-5-20251001");
        String p = CliValueClient.combinedPrompt(req);
        assertThat(p).contains("JSON").contains("code").contains("[A-Z]{4}-\\d{4}").contains("startsWith");
    }
}
