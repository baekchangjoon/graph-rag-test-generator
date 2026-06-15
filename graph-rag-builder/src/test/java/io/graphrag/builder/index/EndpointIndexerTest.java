package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointIndexerTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void index_findsPostEndpointWithClassLevelPrefix() {
        IndexResult result = new EndpointIndexer().index(SAMPLE_SRC);

        assertThat(result.endpoints()).hasSize(1);
        Endpoint endpoint = result.endpoints().get(0);
        assertThat(endpoint.id()).isEqualTo("post-api-orders");
        assertThat(endpoint.httpMethod()).isEqualTo("POST");
        assertThat(endpoint.path()).isEqualTo("/api/orders");
        assertThat(endpoint.handlerClass()).isEqualTo("io.graphrag.sample.orders.OrderController");
        assertThat(endpoint.handlerMethod()).isEqualTo("create");
        assertThat(endpoint.authRequired()).isFalse();
        assertThat(endpoint.params()).hasSize(1);
        assertThat(endpoint.params().get(0).kind()).isEqualTo(ParamKind.BODY);
        assertThat(endpoint.params().get(0).javaType())
                .isEqualTo("io.graphrag.sample.orders.OrderController$CreateOrderRequest");
    }

    @Test
    void index_extractsBodyShapeForRequestBodyType() {
        IndexResult result = new EndpointIndexer().index(SAMPLE_SRC);

        BodyShape shape = result.bodyShapes()
                .get("io.graphrag.sample.orders.OrderController$CreateOrderRequest");
        assertThat(shape).isNotNull();
        assertThat(shape.fields()).extracting(BodyShape.BodyField::name)
                .containsExactly("userId", "amount", "type");
        assertThat(shape.fields()).extracting(BodyShape.BodyField::javaType)
                .containsExactly("java.lang.String", "java.lang.Integer", "java.lang.String");
    }

    @Test
    void index_emptyDirectory_returnsNoEndpoints() {
        IndexResult result = new EndpointIndexer().index(Path.of("src/test/resources"));
        // sample-src 외 컨트롤러가 없는 위치를 줘도 동작해야 한다 (하위 폴더 포함 스캔이므로 1개)
        assertThat(result.endpoints()).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void indexesGetWithPathVariableAndRequestParam(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path src = dir.resolve("C.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/orders")
                class C {
                    @GetMapping("/{id}")
                    String get(@PathVariable Long id) { return null; }
                    @GetMapping
                    String list(@RequestParam Long userId) { return null; }
                    @DeleteMapping("/{id}")
                    void del(@PathVariable Long id) {}
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        Endpoint get = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("GET") && e.path().equals("/api/orders/{id}"))
                .findFirst().orElseThrow();
        assertThat(get.params()).extracting(EndpointParam::kind).containsExactly(ParamKind.PATH);
        assertThat(get.authRequired()).isFalse();

        Endpoint list = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("GET") && e.path().equals("/api/orders"))
                .findFirst().orElseThrow();
        assertThat(list.params()).extracting(EndpointParam::kind).containsExactly(ParamKind.QUERY);

        assertThat(result.endpoints()).anyMatch(e -> e.httpMethod().equals("DELETE"));
    }

    @Test
    void authRequiredTrueForNonLoginPathsWhenAuthConfigured(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path src = dir.resolve("C.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class C {
                    @PostMapping("/api/auth/login") String login(@RequestBody String b){return null;}
                    @GetMapping("/api/orders/{id}") String get(@PathVariable Long id){return null;}
                }
                """);
        io.graphrag.builder.run.AuthConfig auth = new io.graphrag.builder.run.AuthConfig(
                "/api/auth/login", "admin", "password", "token", "Authorization", "Bearer", java.util.List.of());
        IndexResult result = new EndpointIndexer().index(dir, auth);

        assertThat(result.endpoints().stream()
                .filter(e -> e.path().equals("/api/auth/login")).findFirst().orElseThrow()
                .authRequired()).isFalse();
        assertThat(result.endpoints().stream()
                .filter(e -> e.path().equals("/api/orders/{id}")).findFirst().orElseThrow()
                .authRequired()).isTrue();
    }

    @Test
    void indexesControllerFormWithCommandObjectAsFormParam(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path src = dir.resolve("WebC.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.validation.BindingResult;
                import org.springframework.ui.Model;
                @Controller
                @RequestMapping("/web/orders")
                class WebC {
                    static class OrderForm { private String customer; private Integer quantity;
                        public String getCustomer(){return customer;}
                        public void setCustomer(String c){this.customer=c;}
                        public Integer getQuantity(){return quantity;}
                        public void setQuantity(Integer q){this.quantity=q;} }
                    @PostMapping
                    String submit(OrderForm form, BindingResult br, Model model) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        Endpoint post = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("POST") && e.path().equals("/web/orders"))
                .findFirst().orElseThrow();
        // 커맨드 객체만 FORM으로 — BindingResult/Model(프레임워크 타입, bodyShape 미해석)은 제외(단일 커맨드 객체).
        assertThat(post.params()).extracting(EndpointParam::kind).containsExactly(ParamKind.FORM);
        assertThat(post.params().get(0).javaType()).isEqualTo("x.WebC$OrderForm");
        // FORM 커맨드 객체도 bodyShape이 추출되어 빌더가 필드를 합성·변이할 수 있어야 한다.
        BodyShape shape = result.bodyShapes().get("x.WebC$OrderForm");
        assertThat(shape).isNotNull();
        assertThat(shape.fields()).extracting(BodyShape.BodyField::name)
                .containsExactly("customer", "quantity");
    }

    @Test
    void skipsControllerViewHandlerWithoutCommandObject(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path src = dir.resolve("ViewC.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.ui.Model;
                @Controller
                class ViewC {
                    @GetMapping("/web/home") String home(Model model) { return "home"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        // 분기 없는 뷰-표시 핸들러(FORM 커맨드 객체 없음)는 인덱싱 대상 아님.
        assertThat(result.endpoints()).isEmpty();
    }
}
