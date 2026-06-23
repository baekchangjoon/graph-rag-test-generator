package io.graphrag.builder.oracle;

import java.util.List;
import java.util.Map;

/** 테스트용 결정적 LlmValueClient — 고정 응답 + 호출 카운터/예외 토글. */
public final class FakeValueClient implements LlmValueClient {
    private final LlmFieldValues response;
    private final boolean throwOnCall;
    public int calls = 0;

    public FakeValueClient(LlmFieldValues response) {
        this(response, false);
    }

    public FakeValueClient(LlmFieldValues response, boolean throwOnCall) {
        this.response = response;
        this.throwOnCall = throwOnCall;
    }

    @Override
    public LlmFieldValues generate(LlmRequest request) {
        calls++;
        if (throwOnCall) {
            throw new RuntimeException("simulated API failure");
        }
        return response;
    }

    public static FakeValueClient of(String field, String value) {
        return new FakeValueClient(new LlmFieldValues(Map.of(field, List.of(value))));
    }
}
