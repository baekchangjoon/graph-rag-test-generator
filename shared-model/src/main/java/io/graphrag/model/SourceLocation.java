package io.graphrag.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 소스 코드 위치. {@link CapturedSql#sourceLocation()} 등에서 사용.
 *
 * <p>JSON 표현에서는 SCHEMAS.md에 따라 {@code "class"} 필드명을 사용한다 (Java keyword 회피용 `className`).
 */
public record SourceLocation(
        @JsonProperty("class") String className,
        String method,
        int line) {

    public SourceLocation {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(method, "method");
    }
}
