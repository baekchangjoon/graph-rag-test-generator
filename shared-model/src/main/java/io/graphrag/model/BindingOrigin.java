package io.graphrag.model;

/**
 * SQL/HTTP/Socket 호출에 들어가는 값의 출처.
 *
 * <p>도구 2의 치환 규칙에서:
 * <ul>
 *   <li>{@link #API_PARAM} — testId 기반 unique 값으로 치환 가능</li>
 *   <li>{@link #LITERAL} — path constraint의 일부, 보존 필수</li>
 *   <li>{@link #COMPUTED} — 케이스별 판단</li>
 * </ul>
 */
public enum BindingOrigin {
    API_PARAM,
    LITERAL,
    COMPUTED,
    CONFIG_PROPERTY,
    GENERATED_BY_FRAMEWORK
}
