package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.exploration.ExplorationBudget;
import io.graphrag.builder.staticanalysis.domain.CallGraph;
import io.graphrag.builder.staticanalysis.domain.ClassRole;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.builder.staticanalysis.domain.Parameter;
import io.graphrag.builder.staticanalysis.domain.ReturnType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAnalysisPathExplorerTest {

    private static final ExplorationBudget BUDGET = new ExplorationBudget(10, Duration.ofSeconds(5));

    private static Endpoint ep() {
        return new Endpoint("GET:/owners/{id}", HttpMethod.GET, "/owners/{id}",
                "petclinic", "com.x.OwnerCtl", "get", false, List.of());
    }

    private static DomainAnalysisResult domain(Endpoint endpoint) {
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        return new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(endpoint.handlerClass() + "#" + endpoint.handlerMethod(),
                        new MethodAnalysis(endpoint.handlerClass(), endpoint.handlerMethod(),
                                List.of(id), List.of(), List.of(), ReturnType.of("void"))),
                CallGraph.empty());
    }

    @Test
    void name_returns_static_ast() {
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(domain(ep()), BoundaryValueConfig.defaults());
        assertThat(explorer.name()).isEqualTo("static-ast");
    }

    @Test
    void proposeInputs_returns_happy_first() {
        Endpoint endpoint = ep();
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(domain(endpoint), BoundaryValueConfig.defaults());
        List<SampleInput> inputs = explorer.proposeInputs(endpoint, BUDGET);
        assertThat(inputs).isNotEmpty();
        assertThat(inputs.get(0).pathParams()).containsExactly(Map.entry("id", "1"));
    }

    @Test
    void proposeInputs_respects_budget_maxInputs() {
        Endpoint endpoint = ep();
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(domain(endpoint), BoundaryValueConfig.defaults());
        List<SampleInput> inputs =
                explorer.proposeInputs(endpoint, new ExplorationBudget(2, Duration.ofSeconds(5)));
        assertThat(inputs).hasSize(2);
    }

    @Test
    void proposeInputs_deterministic() {
        Endpoint endpoint = ep();
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(domain(endpoint), BoundaryValueConfig.defaults());
        List<SampleInput> r1 = explorer.proposeInputs(endpoint, BUDGET);
        List<SampleInput> r2 = explorer.proposeInputs(endpoint, BUDGET);
        assertThat(r1).isEqualTo(r2);
    }

    @Test
    void proposeInputs_returns_empty_when_method_analysis_missing() {
        Endpoint endpoint = ep();
        DomainAnalysisResult empty = new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(),
                CallGraph.empty());
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(empty, BoundaryValueConfig.defaults());
        assertThat(explorer.proposeInputs(endpoint, BUDGET)).isEmpty();
    }
}
