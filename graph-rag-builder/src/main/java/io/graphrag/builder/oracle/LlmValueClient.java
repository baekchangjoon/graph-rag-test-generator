package io.graphrag.builder.oracle;

/** 교체가능 LLM 값 생성 클라이언트(실제 Anthropic / 테스트 Fake). 결정적 호출(temperature 0). */
public interface LlmValueClient {
    LlmFieldValues generate(LlmRequest request);
}
