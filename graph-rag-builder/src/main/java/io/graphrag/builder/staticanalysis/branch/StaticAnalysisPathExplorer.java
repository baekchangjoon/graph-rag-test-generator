package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.exploration.ExplorationBudget;
import io.graphrag.builder.exploration.PathExplorer;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.model.Endpoint;
import io.graphrag.model.SampleInput;

import java.util.List;
import java.util.Objects;

/**
 * {@link PathExplorer} SPI implementation that surfaces the per-endpoint inputs
 * produced by {@link SampleInputGenerator}. Distinct from {@link BranchAnalyzer}
 * in that it returns only the {@link SampleInput} list (no slug / status / queue
 * — those are internal to the JSON file pipeline). Returns an empty list if the
 * endpoint's handler has no {@link MethodAnalysis} entry.
 */
public final class StaticAnalysisPathExplorer implements PathExplorer {

    private final DomainAnalysisResult domain;
    private final BoundaryValueConfig config;

    public StaticAnalysisPathExplorer(DomainAnalysisResult domain, BoundaryValueConfig config) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override public String name() { return "static-ast"; }

    @Override
    public List<SampleInput> proposeInputs(Endpoint endpoint, ExplorationBudget budget) {
        MethodAnalysis ma = domain.methodAnalyses()
                .get(endpoint.handlerClass() + "#" + endpoint.handlerMethod());
        if (ma == null) return List.of();

        List<NamedSampleInput> generated = SampleInputGenerator.generate(
                endpoint, ma, config, ManualReviewSink.discarding());
        int cap = Math.min(generated.size(), budget.maxInputs());
        return generated.subList(0, cap).stream().map(NamedSampleInput::input).toList();
    }
}
