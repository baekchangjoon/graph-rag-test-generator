package io.graphrag.model;

/** 오케스트레이터(사람/LLM)가 도구 2에 보내는 요청. */
public record GenerationRequest(
        String endpointId,
        String pathId,
        String testClassName,
        String packageName,
        AuthMode authMode) {
}
