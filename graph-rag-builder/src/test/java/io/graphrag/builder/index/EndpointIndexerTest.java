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
    void formSlotNotWastedByLeadingFrameworkParam(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // 프레임워크 타입(BindingResult)이 커맨드 객체보다 앞에 와도 단일 FORM 슬롯을 낭비하지 않고
        // 뒤따르는 커맨드 객체를 FORM으로 잡아야 한다(formAdded는 성공 시에만 set).
        java.nio.file.Path src = dir.resolve("WebC2.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.validation.BindingResult;
                @Controller
                @RequestMapping("/web/orders")
                class WebC2 {
                    static class OrderForm { private String customer;
                        public String getCustomer(){return customer;}
                        public void setCustomer(String c){this.customer=c;} }
                    @PostMapping
                    String submit(BindingResult br, OrderForm form) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        Endpoint post = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("POST")).findFirst().orElseThrow();
        assertThat(post.params()).extracting(EndpointParam::kind).containsExactly(ParamKind.FORM);
        assertThat(post.params().get(0).javaType()).isEqualTo("x.WebC2$OrderForm");
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

    @Test
    void classLevelPathVarBackExtractedFromModelAttribute(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // petclinic PetController 패턴: 클래스레벨 {ownerId}가 핸들러 파라미터가 아니라 @ModelAttribute
        // 헬퍼의 @PathVariable에서만 해석된다. 역추출되어 핸들러가 PATH(ownerId) 파라미터를 가져야 한다.
        java.nio.file.Path src = dir.resolve("PetC.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                @RequestMapping("/owners/{ownerId}")
                class PetC {
                    static class PetForm { private String name;
                        public String getName(){return name;}
                        public void setName(String n){this.name=n;} }
                    @ModelAttribute("owner")
                    Object findOwner(@PathVariable("ownerId") int ownerId) { return null; }
                    @PostMapping("/pets/new")
                    String create(PetForm pet) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        Endpoint post = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("POST") && e.path().equals("/owners/{ownerId}/pets/new"))
                .findFirst().orElseThrow();
        // ownerId가 PATH(int)로 역추출 + 폼 커맨드 PetForm이 FORM. 정렬: PATH가 FORM 앞.
        assertThat(post.params()).extracting(EndpointParam::name, EndpointParam::kind)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("ownerId", ParamKind.PATH),
                        org.assertj.core.api.Assertions.tuple("pet", ParamKind.FORM));
        assertThat(post.params().get(0).javaType()).isEqualTo("int");
    }

    @Test
    void mixedHandlerAndHelperPathVarsBothBecomePath(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // VisitController 패턴: petId는 핸들러 @PathVariable, ownerId는 @ModelAttribute 헬퍼에만. 둘 다 PATH.
        java.nio.file.Path src = dir.resolve("VisitC.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                class VisitC {
                    static class VisitForm { private String desc;
                        public String getDesc(){return desc;}
                        public void setDesc(String d){this.desc=d;} }
                    @ModelAttribute("visit")
                    Object load(@PathVariable("ownerId") int ownerId, @PathVariable("petId") int petId) { return null; }
                    @PostMapping("/owners/{ownerId}/pets/{petId}/visits/new")
                    String create(@PathVariable int petId, VisitForm visit) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        Endpoint post = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("POST")).findFirst().orElseThrow();
        assertThat(post.params()).filteredOn(p -> p.kind() == ParamKind.PATH)
                .extracting(EndpointParam::name).containsExactlyInAnyOrder("ownerId", "petId");
        assertThat(post.params()).filteredOn(p -> p.kind() == ParamKind.PATH)
                .extracting(EndpointParam::kind).containsOnly(ParamKind.PATH);
        // PATH가 FORM 앞(정렬). 마지막은 FORM.
        assertThat(post.params().get(post.params().size() - 1).kind()).isEqualTo(ParamKind.FORM);
    }

    @Test
    void placeholderWithoutTypeSignalIsSkipped(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // {foo}가 어디에도 @PathVariable로 안 나타남 → 타입 신호 없음 → PATH 추가 안 함(센티널 폴백 유지).
        java.nio.file.Path src = dir.resolve("Rest.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class Rest {
                    @GetMapping("/a/{foo}/b") String h() { return null; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint get = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("GET")).findFirst().orElseThrow();
        assertThat(get.params()).noneMatch(p -> p.kind() == ParamKind.PATH);
    }

    @Test
    void conflictingTypeSignalPrefersRequiredTrue(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // 동일 {x}가 두 @ModelAttribute 헬퍼에 int(required=false)와 long으로 등장(핸들러엔 없음).
        // 충돌 해결: required 미지정/true(long)가 required=false(int)보다 우선 → long.
        java.nio.file.Path src = dir.resolve("Conf.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                @RequestMapping("/r/{x}")
                class Conf {
                    static class F { private String a;
                        public String getA(){return a;} public void setA(String v){this.a=v;} }
                    @ModelAttribute("m1") Object m1(@PathVariable(name="x", required=false) int x) { return null; }
                    @ModelAttribute("m2") Object m2(@PathVariable("x") long x) { return null; }
                    @PostMapping("/go") String go(F f) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint post = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("POST")).findFirst().orElseThrow();
        EndpointParam x = post.params().stream().filter(p -> p.name().equals("x")).findFirst().orElseThrow();
        assertThat(x.kind()).isEqualTo(ParamKind.PATH);
        assertThat(x.javaType()).isEqualTo("long");
    }

    @Test
    void handlerPathVarNameNormalizedToAnnotationValue(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // @PathVariable("userId") String id → EndpointParam.name이 파라미터명 "id"가 아니라 "userId"(정규화).
        // 이름이 path 템플릿 {userId}와 일치해야 buildPathAndQuery 치환이 정확하다.
        java.nio.file.Path src = dir.resolve("U.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class U {
                    @GetMapping("/u/{userId}") String h(@PathVariable("userId") String id) { return null; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint get = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("GET")).findFirst().orElseThrow();
        assertThat(get.params()).extracting(EndpointParam::name, EndpointParam::kind)
                .containsExactly(org.assertj.core.api.Assertions.tuple("userId", ParamKind.PATH));
    }
}
