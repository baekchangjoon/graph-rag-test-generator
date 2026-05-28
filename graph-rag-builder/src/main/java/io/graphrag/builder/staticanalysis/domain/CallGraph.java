package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-project call edges only. {@code edges()} key is {@code "{classFqn}#{methodName}"};
 * value is the deterministic list of callee keys reachable in one hop.
 * External-library calls are excluded.
 */
public record CallGraph(Map<String, List<String>> edges) {

    public CallGraph {
        Objects.requireNonNull(edges, "edges");
        // Defensive deep copy to keep the record truly immutable.
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        edges.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        edges = java.util.Collections.unmodifiableMap(copy);
    }

    public static CallGraph empty() {
        return new CallGraph(Map.of());
    }
}
