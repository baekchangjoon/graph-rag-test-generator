package io.graphrag.discovery.output;

import io.graphrag.discovery.DiscoveredHandler;
import io.graphrag.discovery.HandlerParam;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExploredPathBuilderTest {

    @Test
    void get_handler_yields_exactly_one_happy_path() {
        DiscoveredHandler h = new DiscoveredHandler(HttpMethod.GET, "/api/vets",
                "com.example.VetController", "list", List.of(), List.of(), false);
        Endpoint ep = EndpointBuilder.build(h, "petclinic");

        List<ExploredPath> paths = ExploredPathBuilder.build(h, ep, "v1");

        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).id()).isEqualTo("static_list_happy");
        assertThat(paths.get(0).exitStatus()).isEqualTo(200);
        assertThat(paths.get(0).endpointId()).isEqualTo("GET:/api/vets");
    }

    @Test
    void post_handler_predicts_201_for_happy() {
        DiscoveredHandler h = new DiscoveredHandler(HttpMethod.POST, "/api/owners",
                "com.example.OwnerController", "create", List.of(), List.of(), true);
        Endpoint ep = EndpointBuilder.build(h, "petclinic");

        ExploredPath happy = ExploredPathBuilder.build(h, ep, "v1").get(0);

        assertThat(happy.exitStatus()).isEqualTo(201);
        // hasRequestBody → body is an empty map (placeholder; user populates real body later)
        assertThat(happy.sampleInput().body()).isNotNull();
    }

    @Test
    void numeric_path_param_spawns_one_happy_plus_three_boundary_variants() {
        DiscoveredHandler h = new DiscoveredHandler(HttpMethod.GET,
                "/api/owners/{ownerId}", "com.example.OwnerController", "find",
                List.of(new HandlerParam("ownerId", "Integer", HandlerParam.ParamSource.PATH)),
                List.of(), false);
        Endpoint ep = EndpointBuilder.build(h, "petclinic");

        List<ExploredPath> paths = ExploredPathBuilder.build(h, ep, "v1");

        // happy + 3 boundary mutations (0, -1, 2147483647, "") minus the happy "1"
        assertThat(paths).hasSize(5);
        assertThat(paths.stream().map(ExploredPath::id))
                .containsExactly(
                        "static_find_happy",
                        "static_find_ownerId-0",
                        "static_find_ownerId-neg1",
                        "static_find_ownerId-2147483647",
                        "static_find_ownerId-empty");

        ExploredPath emptyVariant = paths.get(4);
        assertThat(emptyVariant.sampleInput().pathParams()).containsEntry("ownerId", "");
        assertThat(emptyVariant.exitStatus()).isEqualTo(400);  // missing-param Spring binding error

        ExploredPath negVariant = paths.get(2);
        assertThat(negVariant.sampleInput().pathParams()).containsEntry("ownerId", "-1");
        assertThat(negVariant.exitStatus()).isEqualTo(404);  // record-not-found prediction
    }

    @Test
    void string_path_param_does_not_spawn_variants() {
        DiscoveredHandler h = new DiscoveredHandler(HttpMethod.GET,
                "/api/users/{username}", "com.example.UserController", "find",
                List.of(new HandlerParam("username", "String", HandlerParam.ParamSource.PATH)),
                List.of(), false);
        Endpoint ep = EndpointBuilder.build(h, "petclinic");

        assertThat(ExploredPathBuilder.build(h, ep, "v1")).hasSize(1);
    }

    @Test
    void two_runs_with_same_input_produce_identical_paths_ids() {
        // R6 idempotency: a fresh ExploredPath stream must be byte-identical when re-run.
        DiscoveredHandler h = new DiscoveredHandler(HttpMethod.GET,
                "/api/x/{id}", "X", "m",
                List.of(new HandlerParam("id", "long", HandlerParam.ParamSource.PATH)),
                List.of(), false);
        Endpoint ep = EndpointBuilder.build(h, "petclinic");

        List<ExploredPath> first = ExploredPathBuilder.build(h, ep, "v1");
        List<ExploredPath> second = ExploredPathBuilder.build(h, ep, "v1");

        assertThat(first.stream().map(ExploredPath::id).toList())
                .isEqualTo(second.stream().map(ExploredPath::id).toList());
    }
}
