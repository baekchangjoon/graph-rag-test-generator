package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 3 orchestrator: walks the endpoints from a {@link DomainAnalysisResult},
 * generates {@link NamedSampleInput}s per endpoint, and wraps them into
 * {@link ExploredPath}s — applying the per-endpoint cap and the exclude-paths
 * filter the orchestrator uses for coverage-feedback re-iteration.
 */
public final class BranchAnalyzer {

    private BranchAnalyzer() {}

    public static BranchAnalysisResult analyze(
            DomainAnalysisResult domain,
            String codeVersion,
            int maxPathsPerEndpoint,
            BoundaryValueConfig cfg,
            Set<String> excludeEndpointIds) {

        ManualReviewSink.CollectingSink sink = ManualReviewSink.collecting();
        List<ExploredPath> all = new ArrayList<>();

        for (Endpoint ep : domain.endpoints()) {
            if (excludeEndpointIds.contains(ep.id())) continue;

            MethodAnalysis ma = domain.methodAnalyses()
                    .get(ep.handlerClass() + "#" + ep.handlerMethod());

            List<NamedSampleInput> inputs = (ma == null)
                    ? syntheticHappyOnly(ep, sink)
                    : SampleInputGenerator.generate(ep, ma, cfg, sink);

            List<ExploredPath> built = ExploredPathBuilder.build(ep, inputs, codeVersion);
            if (built.size() > maxPathsPerEndpoint) {
                built = built.subList(0, maxPathsPerEndpoint);
            }
            all.addAll(built);
        }
        return new BranchAnalysisResult(all, sink.frozen());
    }

    private static List<NamedSampleInput> syntheticHappyOnly(Endpoint ep, ManualReviewSink sink) {
        sink.accept(new ManualReviewItem(
                "missing_method_analysis",
                "endpoint handler not present in DomainAnalysisResult.methodAnalyses",
                ep.handlerClass() + "#" + ep.handlerMethod()));
        int status = ep.method() == HttpMethod.POST ? 201 : 200;
        return List.of(new NamedSampleInput("happy", status,
                new SampleInput(Map.of(), Map.of(), Map.of(), null)));
    }
}
