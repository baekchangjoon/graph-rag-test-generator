package io.graphrag.builder.oracle;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 로컬 CLI(`claude`/`cursor-agent`/`agy`)를 {@code -p}(print/비대화) + {@code --model}로 실행해
 * 값을 생성한다. CLI 로그인 자격증명 사용(ANTHROPIC_API_KEY 불요). 결정성은 캐시가 보장.
 * 시스템+사용자 프롬프트는 -p 모드에 단일 프롬프트로 합쳐 전달하고, stdout에서 JSON을 관용 추출한다.
 */
public final class CliValueClient implements LlmValueClient {
    private static final long TIMEOUT_SEC = 120;

    private final String binary;
    private final String modelId;

    private CliValueClient(String binary, String modelId) {
        this.binary = binary;
        this.modelId = modelId;
    }

    public static CliValueClient of(String binary, String modelId) {
        return new CliValueClient(binary, modelId);
    }

    /**
     * 결정적 커맨드 구성(테스트 가능, 프로세스 비실행). 프롬프트는 마지막 위치 인자.
     * CLI별 비대화 인터페이스가 달라 분기한다 — claude/cursor-agent/agy는 {@code -p --model},
     * kiro-cli는 {@code chat --no-interactive --model --trust-tools=}.
     */
    List<String> buildCommand(String prompt) {
        String name = java.nio.file.Path.of(binary).getFileName().toString();
        if (name.startsWith("kiro")) {
            return List.of(binary, "chat", "--no-interactive", "--model", modelId,
                    "--trust-tools=", prompt);
        }
        return List.of(binary, "-p", "--model", modelId, prompt);
    }

    static String combinedPrompt(LlmRequest request) {
        AnthropicValueClient.PreparedCall call = AnthropicValueClient.prepare(request);
        return call.systemPrompt() + "\n\n" + call.userPrompt();
    }

    @Override
    public LlmFieldValues generate(LlmRequest request) {
        try {
            Process proc = new ProcessBuilder(buildCommand(combinedPrompt(request)))
                    .redirectErrorStream(false)
                    .start();
            String stdout = readAll(proc.getInputStream());
            boolean done = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                throw new IllegalStateException(binary + " -p timed out");
            }
            if (proc.exitValue() != 0) {
                throw new IllegalStateException(binary + " -p exit " + proc.exitValue());
            }
            return LlmJson.parseFields(stdout);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(binary + " -p failed: " + e.getMessage(), e);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
