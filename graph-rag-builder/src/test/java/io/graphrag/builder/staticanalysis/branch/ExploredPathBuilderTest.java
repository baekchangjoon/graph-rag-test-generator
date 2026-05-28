package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExploredPathBuilderTest {

    private static Endpoint ep(HttpMethod m, String path, String klass, String mname) {
        return new Endpoint(m.name() + ":" + path, m, path, "petclinic",
                klass, mname, false, List.of());
    }

    private static NamedSampleInput happy(int status) {
        return new NamedSampleInput("happy", status,
                new SampleInput(Map.of(), Map.of(), Map.of(), null));
    }

    private static NamedSampleInput boundary(String slug, int status) {
        return new NamedSampleInput(slug, status,
                new SampleInput(Map.of(), Map.of("id", "-1"), Map.of(), null));
    }

    @Test
    void slug_uses_handler_method_name() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        List<ExploredPath> out = ExploredPathBuilder.build(
                endpoint, List.of(happy(200), boundary("id-neg1", 404)), "v1");

        assertThat(out.get(0).id()).isEqualTo("static_get_happy");
        assertThat(out.get(1).id()).isEqualTo("static_get_id-neg1");
    }

    @Test
    void happy_uses_200_for_GET() {
        Endpoint endpoint = ep(HttpMethod.GET, "/vets", "com.x.VetController", "list");
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(happy(200)), "v1").get(0);
        assertThat(p.exitStatus()).isEqualTo(200);
    }

    @Test
    void happy_uses_201_for_POST() {
        Endpoint endpoint = ep(HttpMethod.POST, "/owners", "com.x.OwnerController", "create");
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(happy(201)), "v1").get(0);
        assertThat(p.exitStatus()).isEqualTo(201);
    }

    @Test
    void numeric_boundary_predicts_404() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        ExploredPath p = ExploredPathBuilder.build(
                endpoint, List.of(happy(200), boundary("id-neg1", 404)), "v1").get(1);
        assertThat(p.exitStatus()).isEqualTo(404);
    }

    @Test
    void empty_string_boundary_predicts_400() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners", "com.x.OwnerController", "search");
        NamedSampleInput empty = new NamedSampleInput("q-empty", 400,
                new SampleInput(Map.of(), Map.of(), Map.of("q", ""), null));
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(empty), "v1").get(0);
        assertThat(p.exitStatus()).isEqualTo(400);
    }

    @Test
    void discoveredBy_is_MANUAL() {
        Endpoint endpoint = ep(HttpMethod.GET, "/vets", "com.x.VetController", "list");
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(happy(200)), "v1").get(0);
        assertThat(p.discoveredBy()).isEqualTo(PathExplorerKind.MANUAL);
    }

    @Test
    void coverage_signature_matches_convention() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        List<ExploredPath> out = ExploredPathBuilder.build(
                endpoint, List.of(happy(200), boundary("id-neg1", 404)), "v1");
        assertThat(out.get(0).coverageSignature()).isEqualTo("static:GET:/owners/{id}:happy");
        assertThat(out.get(1).coverageSignature()).isEqualTo("static:GET:/owners/{id}:id-neg1");
    }

    @Test
    void branches_taken_uses_handler_class_dot_method_colon_slug() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(happy(200)), "v1").get(0);
        assertThat(p.branchesTaken())
                .containsExactly("com.x.OwnerController.get:happy");
    }
}
