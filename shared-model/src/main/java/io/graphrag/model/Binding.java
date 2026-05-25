package io.graphrag.model;

import java.util.Objects;

/**
 * SQL/HTTP/Socket 호출의 파라미터 바인딩.
 *
 * <p>{@link #origin()}이 {@link BindingOrigin#API_PARAM}이면 도구 2가 unique 값으로 치환,
 * {@link BindingOrigin#LITERAL}이면 보존한다.
 *
 * @param value JSON-호환 값 (String, Number, Boolean, null, List, Map 등)
 * @param originRef API_PARAM의 경우 "apiParam.userId" 형식. LITERAL이면 null 가능.
 */
public record Binding(
        int position,
        Object value,
        BindingOrigin origin,
        String originRef) {

    public Binding {
        Objects.requireNonNull(origin, "origin");
    }
}
