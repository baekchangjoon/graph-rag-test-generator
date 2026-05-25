package io.graphrag.model;

import java.util.List;
import java.util.Objects;

/**
 * Symbolic path constraint. JDart 같은 콘콜릭 엔진이 채움. 다른 엔진은 null.
 *
 * <p>도구 2의 unique ID 치환에서 constraint를 충족하는 범위 안에서 값을 선택할 때 사용.
 */
public record PathConstraint(
        String expression,
        List<String> variables) {

    public PathConstraint {
        Objects.requireNonNull(expression, "expression");
        variables = List.copyOf(Objects.requireNonNullElse(variables, List.of()));
    }
}
