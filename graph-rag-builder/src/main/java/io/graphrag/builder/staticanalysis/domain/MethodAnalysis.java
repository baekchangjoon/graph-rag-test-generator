package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Objects;

/**
 * Per-method analysis bundle, keyed by {@code "{classFqn}#{methodName}"} in
 * {@link DomainAnalysisResult#methodAnalyses()}.
 */
public record MethodAnalysis(
        String classFqn,
        String methodName,
        List<Parameter> parameters,
        List<Branch> branches,
        List<MethodCall> outgoingCalls,
        ReturnType returnType) {

    public MethodAnalysis {
        Objects.requireNonNull(classFqn, "classFqn");
        Objects.requireNonNull(methodName, "methodName");
        parameters    = List.copyOf(Objects.requireNonNull(parameters,    "parameters"));
        branches      = List.copyOf(Objects.requireNonNull(branches,      "branches"));
        outgoingCalls = List.copyOf(Objects.requireNonNull(outgoingCalls, "outgoingCalls"));
        Objects.requireNonNull(returnType, "returnType");
    }

    /** Convenience: key used in {@link DomainAnalysisResult#methodAnalyses()}. */
    public String key() {
        return classFqn + "#" + methodName;
    }
}
