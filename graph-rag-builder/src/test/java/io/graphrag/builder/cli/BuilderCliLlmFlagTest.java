package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuilderCliLlmFlagTest {
    @Test
    void flagAbsentMeansOff() {  // REQ-009
        var opts = BuilderCli.parseArgs(new String[]{"--sut-id", "x"});
        assertThat(opts.containsKey("--llm-oracle")).isFalse();
    }

    @Test
    void flagAndModelParse() {  // REQ-009
        var opts = BuilderCli.parseArgs(
                new String[]{"--llm-oracle", "--llm-model", "claude-sonnet-4-6"});
        assertThat(opts.containsKey("--llm-oracle")).isTrue();
        assertThat(opts.getOrDefault("--llm-model", "claude-haiku-4-5-20251001"))
                .isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void defaultModelWhenFlagOnly() {  // REQ-009
        var opts = BuilderCli.parseArgs(new String[]{"--llm-oracle"});
        assertThat(opts.getOrDefault("--llm-model", "claude-haiku-4-5-20251001"))
                .isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void backendFlagParses() {  // REQ-016
        var opts = BuilderCli.parseArgs(
                new String[]{"--llm-oracle", "--llm-backend", "bedrock", "--llm-cli", "cursor-agent"});
        var llm = new io.graphrag.builder.oracle.LlmOptions(
                opts.containsKey("--llm-oracle"), opts.get("--llm-model"),
                opts.get("--llm-backend"), opts.get("--llm-cli"));
        assertThat(llm.enabled()).isTrue();
        assertThat(llm.backend()).isEqualTo("bedrock");
        assertThat(llm.cli()).isEqualTo("cursor-agent");
        assertThat(llm.model()).isEqualTo("claude-haiku-4-5-20251001");   // 기본
    }

    @Test
    void backendDefaultsToApi() {  // REQ-016
        var opts = BuilderCli.parseArgs(new String[]{"--llm-oracle"});
        var llm = new io.graphrag.builder.oracle.LlmOptions(
                opts.containsKey("--llm-oracle"), opts.get("--llm-model"),
                opts.get("--llm-backend"), opts.get("--llm-cli"));
        assertThat(llm.backend()).isEqualTo("api");
        assertThat(llm.cli()).isEqualTo("claude");
    }
}
