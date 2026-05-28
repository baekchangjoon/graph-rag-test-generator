package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointExtractorTest {

    @Test
    void get_mapping_with_class_prefix_joined() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            @RequestMapping("/owners")
            class C {
                @GetMapping("/{id}")
                Object find(@PathVariable Long id) { return null; }
            }
            """);
        List<Endpoint> eps = EndpointExtractor.extract(cls, "demo.C", "petclinic");
        assertThat(eps).hasSize(1);
        Endpoint ep = eps.get(0);
        assertThat(ep.id()).isEqualTo("GET:/owners/{id}");
        assertThat(ep.method()).isEqualTo(HttpMethod.GET);
        assertThat(ep.path()).isEqualTo("/owners/{id}");
        assertThat(ep.handlerClass()).isEqualTo("demo.C");
        assertThat(ep.handlerMethod()).isEqualTo("find");
        assertThat(ep.project()).isEqualTo("petclinic");
        assertThat(ep.authRequired()).isFalse();
        assertThat(ep.requiredRoles()).isEmpty();
    }

    @Test
    void each_shorthand_annotation_maps_to_method() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            class C {
                @GetMapping("/g")    Object g() { return null; }
                @PostMapping("/p")   Object p() { return null; }
                @PutMapping("/u")    Object u() { return null; }
                @DeleteMapping("/d") Object d() { return null; }
                @PatchMapping("/x")  Object x() { return null; }
            }
            """);
        // The class isn't annotated as a controller; force-process to test mapping logic only.
        List<Endpoint> eps = EndpointExtractor.extractFromClass(cls, "demo.C", "p");
        assertThat(eps).extracting(Endpoint::id)
                .containsExactlyInAnyOrder(
                        "GET:/g", "POST:/p", "PUT:/u", "DELETE:/d", "PATCH:/x");
    }

    @Test
    void request_mapping_with_method_attribute() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            class C {
                @RequestMapping(value = "/r", method = RequestMethod.POST)
                Object r() { return null; }
            }
            """);
        List<Endpoint> eps = EndpointExtractor.extractFromClass(cls, "demo.C", "p");
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).method()).isEqualTo(HttpMethod.POST);
        assertThat(eps.get(0).path()).isEqualTo("/r");
    }

    @Test
    void request_mapping_without_method_is_skipped() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            class C {
                @RequestMapping("/no-method")
                Object x() { return null; }
            }
            """);
        assertThat(EndpointExtractor.extractFromClass(cls, "demo.C", "p")).isEmpty();
    }

    @Test
    void only_controller_annotated_classes_emit_endpoints_via_extract() {
        ClassOrInterfaceDeclaration controller = parseClass("""
            @RestController
            class A { @GetMapping("/a") Object a() { return null; } }
            """);
        ClassOrInterfaceDeclaration service = parseClass("""
            @Service
            class B { @GetMapping("/b") Object b() { return null; } }
            """);
        assertThat(EndpointExtractor.extract(controller, "demo.A", "p")).hasSize(1);
        assertThat(EndpointExtractor.extract(service,    "demo.B", "p")).isEmpty();
    }

    @Test
    void leading_slash_normalized_into_path() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C { @GetMapping("vets") Object v() { return null; } }
            """);
        List<Endpoint> eps = EndpointExtractor.extract(cls, "demo.C", "p");
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).path()).isEqualTo("/vets");
    }

    @Test
    void pre_authorize_hasrole_extracts_role() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/admin")
                @PreAuthorize("hasRole('ADMIN')")
                Object a() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.authRequired()).isTrue();
        assertThat(ep.requiredRoles()).containsExactly("ADMIN");
    }

    @Test
    void pre_authorize_hasanyrole_extracts_all_roles() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.requiredRoles()).containsExactly("ADMIN", "USER");
    }

    @Test
    void pre_authorize_is_authenticated_marks_auth_required_with_no_roles() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @PreAuthorize("isAuthenticated()")
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.authRequired()).isTrue();
        assertThat(ep.requiredRoles()).isEmpty();
    }

    @Test
    void secured_strips_role_prefix() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @Secured({"ROLE_ADMIN", "ROLE_USER"})
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.requiredRoles()).containsExactly("ADMIN", "USER");
    }

    @Test
    void roles_allowed_extracts_roles() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @RolesAllowed({"USER"})
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.requiredRoles()).containsExactly("USER");
    }

    @Test
    void class_level_auth_propagates_to_all_methods() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            @PreAuthorize("hasRole('ADMIN')")
            class C {
                @GetMapping("/x") Object x() { return null; }
                @GetMapping("/y") Object y() { return null; }
            }
            """);
        List<Endpoint> eps = EndpointExtractor.extract(cls, "demo.C", "p");
        assertThat(eps).allMatch(Endpoint::authRequired);
        assertThat(eps).allMatch(e -> e.requiredRoles().equals(List.of("ADMIN")));
    }

    @Test
    void unrecognized_spel_yields_no_roles_but_keeps_auth_required() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @PreAuthorize("@bean.check(#root)")
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.authRequired()).isTrue();
        assertThat(ep.requiredRoles()).isEmpty();
    }

    private static ClassOrInterfaceDeclaration parseClass(String src) {
        return StaticJavaParser.parse(src).findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }
}
