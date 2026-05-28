package io.graphrag.builder.staticanalysis.domain;

import io.graphrag.model.Endpoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of {@link DomainAnalyzer#analyze}.
 *
 * <p>All collections are deterministic:
 * <ul>
 *   <li>{@code endpoints} sorted by {@code (method, path)}.</li>
 *   <li>{@code classRoles}, {@code methodAnalyses} are insertion-ordered
 *       (insertion = path-sorted ParsedFile order).</li>
 * </ul>
 */
public record DomainAnalysisResult(
        List<Endpoint> endpoints,
        Map<String, ClassRole> classRoles,
        Map<String, MethodAnalysis> methodAnalyses,
        CallGraph callGraph) {

    public DomainAnalysisResult {
        endpoints      = List.copyOf(Objects.requireNonNull(endpoints,      "endpoints"));
        // Use unmodifiableMap (not Map.copyOf) to preserve LinkedHashMap insertion order.
        classRoles     = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(classRoles,     "classRoles")));
        methodAnalyses = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(methodAnalyses, "methodAnalyses")));
        Objects.requireNonNull(callGraph, "callGraph");
    }
}
