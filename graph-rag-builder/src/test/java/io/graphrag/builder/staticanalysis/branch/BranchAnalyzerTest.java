package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.staticanalysis.domain.CallGraph;
import io.graphrag.builder.staticanalysis.domain.ClassRole;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.builder.staticanalysis.domain.Parameter;
import io.graphrag.builder.staticanalysis.domain.ReturnType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BranchAnalyzerTest {

    private final BoundaryValueConfig cfg = BoundaryValueConfig.defaults();

    private static Endpoint ep(String klass, String mname, String path, HttpMethod method) {
        return new Endpoint(method.name() + ":" + path, method, path, "petclinic",
                klass, mname, false, List.of());
    }

    private static MethodAnalysis ma(String klass, String mname, List<Parameter> params) {
        return new MethodAnalysis(klass, mname, params,
                List.of(), List.of(), ReturnType.of("void"));
    }

    @Test
    void endpoint_missing_method_analysis_yields_only_happy_and_logs_queue_entry() {
        Endpoint endpoint = ep("com.x.UnknownCtl", "missing", "/foo", HttpMethod.GET);
        DomainAnalysisResult domain = new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(), CallGraph.empty());

        BranchAnalysisResult result = BranchAnalyzer.analyze(
                domain, "v1", 10, cfg, Set.of());

        assertThat(result.paths()).hasSize(1);
        assertThat(result.paths().get(0).id()).isEqualTo("static_missing_happy");
        assertThat(result.paths().get(0).sampleInput().pathParams()).isEmpty();
        assertThat(result.manualReviewQueue()).hasSize(1);
        assertThat(result.manualReviewQueue().get(0).kind()).isEqualTo("missing_method_analysis");
    }

    @Test
    void excludePaths_skips_endpoint_entirely() {
        Endpoint kept = ep("com.x.A", "list", "/a", HttpMethod.GET);
        Endpoint skipped = ep("com.x.B", "list", "/b", HttpMethod.GET);
        Map<String, MethodAnalysis> mas = new LinkedHashMap<>();
        mas.put(kept.handlerClass() + "#" + kept.handlerMethod(), ma(kept.handlerClass(), kept.handlerMethod(), List.of()));
        mas.put(skipped.handlerClass() + "#" + skipped.handlerMethod(), ma(skipped.handlerClass(), skipped.handlerMethod(), List.of()));
        DomainAnalysisResult domain = new DomainAnalysisResult(
                List.of(kept, skipped),
                Map.of(kept.handlerClass(), ClassRole.CONTROLLER,
                       skipped.handlerClass(), ClassRole.CONTROLLER),
                mas, CallGraph.empty());

        BranchAnalysisResult result = BranchAnalyzer.analyze(
                domain, "v1", 10, cfg, Set.of(skipped.id()));

        assertThat(result.paths()).extracting(ExploredPath::endpointId)
                .containsExactly(kept.id());
        assertThat(result.manualReviewQueue()).isEmpty();
    }

    @Test
    void maxPerEndpoint_cap_keeps_happy_first() {
        Endpoint endpoint = ep("com.x.OwnerCtl", "get", "/owners/{id}", HttpMethod.GET);
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        DomainAnalysisResult domain = new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(endpoint.handlerClass() + "#" + endpoint.handlerMethod(),
                        ma(endpoint.handlerClass(), endpoint.handlerMethod(), List.of(id))),
                CallGraph.empty());

        BranchAnalysisResult result = BranchAnalyzer.analyze(domain, "v1",
                /* maxPathsPerEndpoint */ 2, cfg, Set.of());

        assertThat(result.paths()).hasSize(2);
        assertThat(result.paths().get(0).id()).isEqualTo("static_get_happy");
        assertThat(result.paths().get(1).id()).isEqualTo("static_get_id-neg1");
    }

    @Test
    void idempotent_under_repeat_invocation() {
        Endpoint endpoint = ep("com.x.OwnerCtl", "get", "/owners/{id}", HttpMethod.GET);
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        DomainAnalysisResult domain = new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(endpoint.handlerClass() + "#" + endpoint.handlerMethod(),
                        ma(endpoint.handlerClass(), endpoint.handlerMethod(), List.of(id))),
                CallGraph.empty());

        BranchAnalysisResult r1 = BranchAnalyzer.analyze(domain, "v1", 10, cfg, Set.of());
        BranchAnalysisResult r2 = BranchAnalyzer.analyze(domain, "v1", 10, cfg, Set.of());

        assertThat(r1).isEqualTo(r2);
    }
}
