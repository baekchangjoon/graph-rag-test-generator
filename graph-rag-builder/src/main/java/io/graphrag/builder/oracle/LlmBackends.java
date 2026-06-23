package io.graphrag.builder.oracle;

/** `--llm-backend` 값 → LlmValueClient 선택. api(기본)/bedrock/cli 모두 동일 인터페이스 뒤. */
public final class LlmBackends {

    private LlmBackends() {
    }

    /** usable: cache miss 시 호출을 시도해도 되는가(api는 키 유무, bedrock/cli는 best-effort=true). */
    public record Selection(LlmValueClient client, boolean usable) {
    }

    public static Selection create(String backend, String model, String cli) {
        return switch (backend) {
            case "api" -> {
                AnthropicValueClient c = AnthropicValueClient.fromEnv(model);
                yield new Selection(c, c.hasApiKey());
            }
            case "bedrock" -> new Selection(AnthropicValueClient.bedrock(model), true);
            case "cli" -> new Selection(CliValueClient.of(cli, model), true);
            default -> throw new IllegalArgumentException(
                    "unknown --llm-backend: " + backend + " (expected api|bedrock|cli)");
        };
    }
}
