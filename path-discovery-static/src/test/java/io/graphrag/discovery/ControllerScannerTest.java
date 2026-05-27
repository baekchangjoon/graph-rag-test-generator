package io.graphrag.discovery;

import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerScannerTest {

    private static final Path SAMPLES = locateResource("sample-controllers");

    @Test
    void discovers_get_post_put_delete_patch_handlers() throws Exception {
        List<DiscoveredHandler> handlers = ControllerScanner.scan(SAMPLES);

        // OwnerController contributes 6 handlers, VetController contributes 2.
        assertThat(handlers).hasSize(8);

        assertThat(handlers).anySatisfy(h -> {
            assertThat(h.method()).isEqualTo(HttpMethod.GET);
            assertThat(h.path()).isEqualTo("/api/owners");
            assertThat(h.handlerMethod()).isEqualTo("list");
        });
        assertThat(handlers).anySatisfy(h -> {
            assertThat(h.method()).isEqualTo(HttpMethod.GET);
            assertThat(h.path()).isEqualTo("/api/owners/{ownerId}");
            assertThat(h.handlerMethod()).isEqualTo("find");
        });
        assertThat(handlers).anySatisfy(h -> {
            assertThat(h.method()).isEqualTo(HttpMethod.POST);
            assertThat(h.path()).isEqualTo("/api/owners");
            assertThat(h.handlerMethod()).isEqualTo("create");
            assertThat(h.hasRequestBody()).isTrue();
        });
        assertThat(handlers).anySatisfy(h -> {
            assertThat(h.method()).isEqualTo(HttpMethod.PUT);
            assertThat(h.path()).isEqualTo("/api/owners/{ownerId}");
            assertThat(h.handlerMethod()).isEqualTo("update");
        });
        assertThat(handlers).anySatisfy(h -> {
            assertThat(h.method()).isEqualTo(HttpMethod.DELETE);
            assertThat(h.path()).isEqualTo("/api/owners/{ownerId}");
        });
        assertThat(handlers).anySatisfy(h -> {
            assertThat(h.method()).isEqualTo(HttpMethod.PATCH);
            assertThat(h.path()).isEqualTo("/api/vets/{id}");
            assertThat(h.handlerClass()).isEqualTo("com.example.petclinic.VetController");
        });
    }

    @Test
    void discovers_path_variables_with_correct_names() throws Exception {
        List<DiscoveredHandler> handlers = ControllerScanner.scan(SAMPLES);

        DiscoveredHandler find = handlers.stream()
                .filter(h -> h.handlerMethod().equals("find"))
                .findFirst().orElseThrow();
        assertThat(find.pathParams()).hasSize(1);
        assertThat(find.pathParams().get(0).name()).isEqualTo("ownerId");
        assertThat(find.pathParams().get(0).typeName()).isEqualTo("Integer");
        assertThat(find.pathParams().get(0).source()).isEqualTo(HandlerParam.ParamSource.PATH);
    }

    @Test
    void discovers_query_params_and_respects_explicit_value_attribute() throws Exception {
        List<DiscoveredHandler> handlers = ControllerScanner.scan(SAMPLES);

        DiscoveredHandler search = handlers.stream()
                .filter(h -> h.handlerMethod().equals("search"))
                .findFirst().orElseThrow();

        assertThat(search.queryParams()).hasSize(2);
        // First param uses the Java parameter name (no explicit value attribute).
        assertThat(search.queryParams().get(0).name()).isEqualTo("name");
        // Second param's annotation specified value = "max-results", which should override
        // the Java parameter name. Catching this matters because Spring routes by the
        // annotation value, not the Java identifier.
        assertThat(search.queryParams().get(1).name()).isEqualTo("max-results");
    }

    @Test
    void ignores_classes_without_controller_annotation() throws Exception {
        List<DiscoveredHandler> handlers = ControllerScanner.scan(SAMPLES);
        assertThat(handlers).noneMatch(h -> h.handlerClass().endsWith("NotAController"));
    }

    @Test
    void normalizes_paths_with_inconsistent_leading_slashes() throws Exception {
        List<DiscoveredHandler> handlers = ControllerScanner.scan(SAMPLES);
        // Every discovered path must start with "/" so the downstream URL builder doesn't
        // have to compensate for petclinic-style "owners" vs "/owners".
        assertThat(handlers).allMatch(h -> h.path().startsWith("/"));
    }

    private static Path locateResource(String name) {
        try {
            return Path.of(ControllerScannerTest.class.getClassLoader()
                    .getResource(name).toURI());
        } catch (Exception ex) {
            throw new IllegalStateException("missing test resource: " + name, ex);
        }
    }
}
