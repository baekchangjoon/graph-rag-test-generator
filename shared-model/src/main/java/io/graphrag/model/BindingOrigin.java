package io.graphrag.model;

/** SQL 바인딩 값의 유래. docs/04 치환 규칙의 키. */
public enum BindingOrigin {
    /** API 요청 파라미터에서 그대로 전달된 값 → 테스트에서 testId 기반 unique 치환 대상. */
    API_PARAM,
    /** 코드/스키마의 리터럴 → 그대로 보존. */
    LITERAL,
    /** SUT가 계산한 값 (시퀀스, 시간 등) → 케이스별 판단. */
    COMPUTED
}
