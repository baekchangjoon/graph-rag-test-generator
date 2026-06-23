package io.graphrag.builder.oracle;

/** LLM 값 오라클 설정 — enabled(--llm-oracle), model(--llm-model), backend(--llm-backend), cli(--llm-cli). */
public record LlmOptions(boolean enabled, String model, String backend, String cli) {

    public LlmOptions {
        model = model == null || model.isBlank() ? "claude-haiku-4-5-20251001" : model;
        backend = backend == null || backend.isBlank() ? "api" : backend;
        cli = cli == null || cli.isBlank() ? "claude" : cli;
    }

    public static LlmOptions disabled() {
        return new LlmOptions(false, null, null, null);
    }
}
