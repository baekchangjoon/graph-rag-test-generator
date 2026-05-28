package io.graphrag.translator;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathTemplateExpanderTest {

    @Test
    void expand_single_path_param() {
        String out = PathTemplateExpander.expand("/api/owners/{ownerId}",
                Map.of("ownerId", "1"), Map.of());
        assertThat(out).isEqualTo("/api/owners/1");
    }

    @Test
    void expand_multiple_path_params_preserves_order() {
        String out = PathTemplateExpander.expand("/api/owners/{ownerId}/pets/{petId}",
                Map.of("ownerId", "7", "petId", "42"), Map.of());
        assertThat(out).isEqualTo("/api/owners/7/pets/42");
    }

    @Test
    void expand_no_path_params_returns_template_unchanged() {
        String out = PathTemplateExpander.expand("/api/vets", Map.of(), Map.of());
        assertThat(out).isEqualTo("/api/vets");
    }

    @Test
    void expand_appends_query_params_alphabetically_for_determinism() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("page", "0");
        q.put("size", "20");
        String out = PathTemplateExpander.expand("/api/owners", Map.of(), q);
        assertThat(out).isEqualTo("/api/owners?page=0&size=20");
    }

    @Test
    void expand_combines_path_and_query_params() {
        String out = PathTemplateExpander.expand("/api/owners/{ownerId}/pets",
                Map.of("ownerId", "1"),
                Map.of("status", "active"));
        assertThat(out).isEqualTo("/api/owners/1/pets?status=active");
    }

    @Test
    void expand_url_encodes_query_values_with_special_chars() {
        String out = PathTemplateExpander.expand("/search",
                Map.of(),
                Map.of("q", "a b&c=d"));
        assertThat(out).isEqualTo("/search?q=a+b%26c%3Dd");
    }

    @Test
    void expand_url_encodes_path_param_values() {
        String out = PathTemplateExpander.expand("/api/owners/{name}",
                Map.of("name", "alice smith"), Map.of());
        // Spaces in path segments encode as %20 (not +), per RFC 3986.
        assertThat(out).isEqualTo("/api/owners/alice%20smith");
    }

    @Test
    void expand_throws_when_template_placeholder_unbound() {
        assertThatThrownBy(() -> PathTemplateExpander.expand(
                "/api/owners/{ownerId}", Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerId");
    }

    @Test
    void expand_ignores_pathParam_keys_not_in_template() {
        // Extra keys are allowed (some callers carry headers in the same map).
        String out = PathTemplateExpander.expand("/api/owners",
                Map.of("ownerId", "1"), Map.of());
        assertThat(out).isEqualTo("/api/owners");
    }
}
