package io.graphrag.builder.staticanalysis.domain;

import java.util.Objects;

/**
 * One outgoing method call from a method body. When the symbol solver cannot
 * resolve the target, {@code calleeClassFqn} is {@code null} and {@code resolved}
 * is {@code false}.
 */
public record MethodCall(
        String calleeClassFqn,         // nullable
        String calleeMethodName,
        int line,
        boolean resolved) {

    public MethodCall {
        Objects.requireNonNull(calleeMethodName, "calleeMethodName");
    }
}
