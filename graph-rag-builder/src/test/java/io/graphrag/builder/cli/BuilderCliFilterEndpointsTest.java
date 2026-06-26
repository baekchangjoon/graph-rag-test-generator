package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import io.graphrag.model.ExplorationReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BuilderCliFilterEndpointsTest {

    private BuildConfig config;

    @BeforeEach
    void setUp() {
        config = new BuildConfig(
                Path.of("."), Path.of("."), Path.of("."), Path.of("."),
                "sut-id", "sha",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres", "user", "pass", "db"),
                60, Path.of("."), Path.of("."), Map.of()
        );
        System.clearProperty("GRB_EXPLORER_EMPTY_BODY");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("GRB_EXPLORER_EMPTY_BODY");
    }

    @Test
    void filtersGETEndpointRegardlessOfBodyShape() {
        Endpoint ep = new Endpoint("ep-get", "GET", "/api/get", "Handler", "handle", List.of(), false);
        IndexResult index = new IndexResult(List.of(ep), Map.of(), Set.of());
        IncrementalPlan plan = IncrementalPlan.exploreAll();
        List<ExplorationReport.UnsupportedShape> unsupported = new ArrayList<>();

        List<Endpoint> filtered = BuilderCli.filterEndpoints(index, plan, config, unsupported);

        assertThat(filtered).containsExactly(ep);
        assertThat(unsupported).isEmpty();
    }

    @Test
    void filtersPOSTEndpointWithPathParams() {
        EndpointParam pathParam = new EndpointParam("id", "java.lang.Long", ParamKind.PATH);
        Endpoint ep = new Endpoint("ep-path", "POST", "/api/path/{id}", "Handler", "handle", List.of(pathParam), false);
        IndexResult index = new IndexResult(List.of(ep), Map.of(), Set.of());
        IncrementalPlan plan = IncrementalPlan.exploreAll();
        List<ExplorationReport.UnsupportedShape> unsupported = new ArrayList<>();

        List<Endpoint> filtered = BuilderCli.filterEndpoints(index, plan, config, unsupported);

        assertThat(filtered).containsExactly(ep);
        assertThat(unsupported).isEmpty();
    }

    @Test
    void filtersPOSTEndpointWithBodyShape() {
        EndpointParam bodyParam = new EndpointParam("dto", "com.example.Dto", ParamKind.BODY);
        Endpoint ep = new Endpoint("ep-body", "POST", "/api/body", "Handler", "handle", List.of(bodyParam), false);
        BodyShape bodyShape = new BodyShape("com.example.Dto", List.of());
        IndexResult index = new IndexResult(List.of(ep), Map.of("com.example.Dto", bodyShape), Set.of());
        IncrementalPlan plan = IncrementalPlan.exploreAll();
        List<ExplorationReport.UnsupportedShape> unsupported = new ArrayList<>();

        List<Endpoint> filtered = BuilderCli.filterEndpoints(index, plan, config, unsupported);

        assertThat(filtered).containsExactly(ep);
        assertThat(unsupported).isEmpty();
    }

    @Test
    void skipsPOSTEndpointWithoutBodyShapeOrPathParamsByDefault() {
        EndpointParam bodyParam = new EndpointParam("dto", "com.example.Dto", ParamKind.BODY);
        Endpoint ep = new Endpoint("ep-skip", "POST", "/api/skip", "Handler", "handle", List.of(bodyParam), false);
        IndexResult index = new IndexResult(List.of(ep), Map.of(), Set.of());
        IncrementalPlan plan = IncrementalPlan.exploreAll();
        List<ExplorationReport.UnsupportedShape> unsupported = new ArrayList<>();

        List<Endpoint> filtered = BuilderCli.filterEndpoints(index, plan, config, unsupported);

        assertThat(filtered).isEmpty();
        // Since reflectInstantiate is enabled by default in BuildConfig, it will try to resolve Dto,
        // and because there is no actual class, it will add it to unsupported.
        assertThat(unsupported).isNotEmpty();
    }

    @Test
    void allowsPOSTEndpointWithoutBodyShapeOrPathParamsWhenBypassEnabled() {
        System.setProperty("GRB_EXPLORER_EMPTY_BODY", "1");
        EndpointParam bodyParam = new EndpointParam("dto", "com.example.Dto", ParamKind.BODY);
        Endpoint ep = new Endpoint("ep-bypass", "POST", "/api/bypass", "Handler", "handle", List.of(bodyParam), false);
        IndexResult index = new IndexResult(List.of(ep), Map.of(), Set.of());
        IncrementalPlan plan = IncrementalPlan.exploreAll();
        List<ExplorationReport.UnsupportedShape> unsupported = new ArrayList<>();

        List<Endpoint> filtered = BuilderCli.filterEndpoints(index, plan, config, unsupported);

        assertThat(filtered).containsExactly(ep);
    }
}
