package io.graphrag.builder.oracle;

import com.anthropic.bedrock.backends.BedrockMantleBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;

import java.util.function.Supplier;

/**
 * Anthropic SDK 기반 값 생성. 백엔드 2종을 팩토리로 제공한다 — {@link #fromEnv}(1st-party API,
 * env ANTHROPIC_API_KEY) / {@link #bedrock}(AWS Bedrock Mantle, AWS 자격증명 체인, 모델 ID에
 * {@code anthropic.} 접두). 클라이언트는 lazy — 객체 생성은 키/자격 없이도 성공하고, 실제
 * {@link #generate}에서만 SDK 클라이언트를 만든다(없으면 그 시점 예외 → 호출부 LlmOracle가 삼킴).
 */
public final class AnthropicValueClient implements LlmValueClient {
    private static final long MAX_TOKENS = 1024L;
    private static final String SYSTEM_PROMPT =
            "You generate domain-plausible request field values for API testing. "
            + "Return ONLY JSON of the exact shape "
            + "{\"fields\":[{\"field\":\"<name>\",\"values\":[\"<v1>\",\"<v2>\"]}]}. "
            + "Each value must satisfy the field's stated regex/format and any handler logic "
            + "(e.g. required prefixes seen in the source). No prose.";

    private final String modelId;
    private final Supplier<AnthropicClient> clientFactory;
    private AnthropicClient client;   // lazy

    private AnthropicValueClient(String modelId, Supplier<AnthropicClient> clientFactory) {
        this.modelId = modelId;
        this.clientFactory = clientFactory;
    }

    /** 1st-party Anthropic API 백엔드 (env ANTHROPIC_API_KEY). */
    public static AnthropicValueClient fromEnv(String modelId) {
        return new AnthropicValueClient(modelId, AnthropicOkHttpClient::fromEnv);
    }

    /** AWS Bedrock 백엔드 (Mantle, AWS 자격증명 체인). 모델 ID에 anthropic. 접두 적용. */
    public static AnthropicValueClient bedrock(String modelId) {
        String resolved = modelId.startsWith("anthropic.") ? modelId : "anthropic." + modelId;
        return new AnthropicValueClient(resolved,
                () -> AnthropicOkHttpClient.builder()
                        .backend(BedrockMantleBackend.fromEnv())
                        .build());
    }

    public String modelId() {
        return modelId;
    }

    public boolean hasApiKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    // 핸들러 본문 egress·비용·컨텍스트 한도 방어를 위한 상한. 분기 판단엔 앞부분이면 충분.
    private static final int MAX_SOURCE_CHARS = 4000;

    /** 순수: LLM 호출에 쓸 모델·온도·프롬프트 구성(테스트 가능, API 무호출). */
    static PreparedCall prepare(LlmRequest request) {
        StringBuilder user = new StringBuilder();
        user.append("Endpoint: ").append(request.endpointId()).append('\n');
        user.append("Generate values for these String fields:\n");
        for (var f : request.fields()) {
            user.append("- ").append(f.name());
            String pattern = request.patternByField().get(f.name());
            if (pattern != null) {
                user.append(" (regex: ").append(pattern).append(')');
            }
            if (request.emailFields().contains(f.name())) {
                user.append(" (must be a valid email)");
            }
            user.append('\n');
        }
        // 잔여 위험: 핸들러 소스는 신뢰 불가 입력(SUT가 적대적 주석을 넣을 수 있음)이나, ShapeGate가
        // shape에 실존하는 String 필드만 통과시키고 union-only 기여라 blast radius=선택 필드 값에 한정.
        // 내부 SUT 전용 권고로 수용. 길이는 MAX_SOURCE_CHARS로 캡(egress/비용/컨텍스트 방어).
        String source = request.handlerSource();
        if (source != null && source.length() > MAX_SOURCE_CHARS) {
            source = source.substring(0, MAX_SOURCE_CHARS) + "\n…(truncated)";
        }
        user.append("\nHandler method body:\n").append(source);
        return new PreparedCall(request.modelId(), 0.0, SYSTEM_PROMPT, user.toString());
    }

    // temperature(double)는 SDK에서 deprecated이나, temperature 0은 결정성의 보조 신호로 의도적으로
    // 유지한다(하드 보장은 캐시). 이 deprecation은 상위 SDK 선택이며 본 설계 결함이 아니다.
    @Override
    @SuppressWarnings("deprecation")
    public LlmFieldValues generate(LlmRequest request) {
        if (client == null) {
            client = clientFactory.get();   // 키/자격 없으면 여기서 예외
        }
        PreparedCall call = prepare(request);
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelId))               // 백엔드별 해석 모델 ID(bedrock=접두)
                .maxTokens(MAX_TOKENS)
                .temperature(call.temperature())
                .system(call.systemPrompt())
                .addUserMessage(call.userPrompt())
                .build();
        Message response = client.messages().create(params);
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .findFirst().orElse("");
        return LlmJson.parseFields(text);
    }

    /** 테스트·CLI 공용 파싱 위임. */
    static LlmFieldValues parse(String json) {
        return LlmJson.parseFields(json);
    }

    record PreparedCall(String model, double temperature, String systemPrompt, String userPrompt) {
    }
}
