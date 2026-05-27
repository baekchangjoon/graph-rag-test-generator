package io.graphrag.translator;

import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import io.graphrag.scout.config.CaptureStep;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExploredPathToCaptureStepTest {

    private static Endpoint endpoint(String id, HttpMethod method, String path) {
        return new Endpoint(id, method, path, "petclinic", "Controller", "m", false, List.of());
    }

    private static ExploredPath path(String id, String endpointId, SampleInput input, int status) {
        return new ExploredPath(id, endpointId, PathExplorerKind.MANUAL, input,
                null, List.of(), status, null, "sig-" + id, "v1");
    }

    @Test
    void path_id_is_preserved_verbatim() {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), null);
        ExploredPath p = path("01HZ-some-ulid", "GET:/api/owners", input, 200);
        Endpoint ep = endpoint("GET:/api/owners", HttpMethod.GET, "/api/owners");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        // R2 mitigation: path-id must NOT be rewritten — downstream archive joins
        // CapturedSql.path_id back to this value.
        assertThat(step.pathId()).isEqualTo("01HZ-some-ulid");
    }

    @Test
    void method_and_path_are_taken_from_endpoint_not_sample() {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), null);
        ExploredPath p = path("p", "GET:/api/vets", input, 200);
        Endpoint ep = endpoint("GET:/api/vets", HttpMethod.GET, "/api/vets");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.method()).isEqualTo("GET");
        assertThat(step.path()).isEqualTo("/api/vets");
    }

    @Test
    void path_params_are_substituted_into_template() {
        SampleInput input = new SampleInput(Map.of(), Map.of("ownerId", "1"), Map.of(), null);
        ExploredPath p = path("p", "GET:/api/owners/{ownerId}", input, 200);
        Endpoint ep = endpoint("GET:/api/owners/{ownerId}", HttpMethod.GET,
                "/api/owners/{ownerId}");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.path()).isEqualTo("/api/owners/1");
    }

    @Test
    void query_params_are_appended_in_sorted_order() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("size", "20");
        q.put("page", "0");
        SampleInput input = new SampleInput(Map.of(), Map.of(), q, null);
        ExploredPath p = path("p", "GET:/api/owners", input, 200);
        Endpoint ep = endpoint("GET:/api/owners", HttpMethod.GET, "/api/owners");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.path()).isEqualTo("/api/owners?page=0&size=20");
    }

    @Test
    void expected_status_comes_from_explored_path_exit_status() {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), null);
        ExploredPath p = path("p", "GET:/x", input, 404);
        Endpoint ep = endpoint("GET:/x", HttpMethod.GET, "/x");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.expectedStatus()).isEqualTo(404);
    }

    @Test
    void null_body_yields_null_body_and_null_content_type() {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), null);
        ExploredPath p = path("p", "GET:/x", input, 200);
        Endpoint ep = endpoint("GET:/x", HttpMethod.GET, "/x");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.body()).isNull();
        assertThat(step.contentType()).isNull();
    }

    @Test
    void string_body_passes_through_and_defaults_content_type_to_plain() {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), "raw text");
        ExploredPath p = path("p", "POST:/x", input, 200);
        Endpoint ep = endpoint("POST:/x", HttpMethod.POST, "/x");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.body()).isEqualTo("raw text");
        assertThat(step.contentType()).isEqualTo("text/plain");
    }

    @Test
    void map_body_is_json_serialized_with_application_json() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Alice");
        body.put("age", 30);
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), body);
        ExploredPath p = path("p", "POST:/x", input, 200);
        Endpoint ep = endpoint("POST:/x", HttpMethod.POST, "/x");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.contentType()).isEqualTo("application/json");
        assertThat(step.body()).contains("\"name\":\"Alice\"");
        assertThat(step.body()).contains("\"age\":30");
    }

    @Test
    void list_body_is_json_serialized_with_application_json() {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), List.of(1, 2, 3));
        ExploredPath p = path("p", "POST:/x", input, 200);
        Endpoint ep = endpoint("POST:/x", HttpMethod.POST, "/x");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.contentType()).isEqualTo("application/json");
        assertThat(step.body()).isEqualTo("[1,2,3]");
    }

    @Test
    void number_body_is_serialized_as_json_literal() {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), 42);
        ExploredPath p = path("p", "POST:/x", input, 200);
        Endpoint ep = endpoint("POST:/x", HttpMethod.POST, "/x");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.contentType()).isEqualTo("application/json");
        assertThat(step.body()).isEqualTo("42");
    }

    @Test
    void caller_supplied_content_type_header_wins_over_default() {
        Map<String, String> headers = Map.of("Content-Type", "application/xml");
        Map<String, Object> body = Map.of("a", "b");
        SampleInput input = new SampleInput(headers, Map.of(), Map.of(), body);
        ExploredPath p = path("p", "POST:/x", input, 200);
        Endpoint ep = endpoint("POST:/x", HttpMethod.POST, "/x");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.contentType()).isEqualTo("application/xml");
        // Content-Type is hoisted into its own field and stripped from headers so HttpScout
        // doesn't send a duplicate header.
        assertThat(step.headers()).doesNotContainKey("Content-Type");
    }

    @Test
    void other_headers_pass_through() {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer abc",
                "X-Trace-Id", "trace-1");
        SampleInput input = new SampleInput(headers, Map.of(), Map.of(), null);
        ExploredPath p = path("p", "GET:/x", input, 200);
        Endpoint ep = endpoint("GET:/x", HttpMethod.GET, "/x");

        CaptureStep step = ExploredPathToCaptureStep.convert(p, ep);

        assertThat(step.headers()).containsEntry("Authorization", "Bearer abc");
        assertThat(step.headers()).containsEntry("X-Trace-Id", "trace-1");
    }

    @Test
    void mismatched_path_endpoint_pair_throws() {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), null);
        ExploredPath p = path("p", "GET:/api/owners", input, 200);
        Endpoint ep = endpoint("GET:/api/vets", HttpMethod.GET, "/api/vets");

        assertThatThrownBy(() -> ExploredPathToCaptureStep.convert(p, ep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint_id");
    }
}
