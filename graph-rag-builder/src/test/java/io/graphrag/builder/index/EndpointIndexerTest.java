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

    /**
     * 이 테스트는 원래 {@code src/test/resources}를 "컨트롤러가 (거의) 없는 위치"로 가정해
     * {@code <= 1}을 단언했는데, 그 뒤 provenance fixture 컨트롤러들이 그 아래에 커밋되면서 가정이
     * 깨져 상시 실패하게 됐다(13개 검출). 단언 대상이던 계약은 "컨트롤러가 없는 디렉토리를 줘도
     * 예외 없이 빈 결과를 낸다"이므로, 실제로 비어 있는 임시 디렉토리로 바꿔 그 계약을 정확히
     * (그리고 더 강하게 — {@code isEmpty()}) 검증한다.
     */
    @Test
    void index_emptyDirectory_returnsNoEndpoints(@org.junit.jupiter.api.io.TempDir Path emptyDir) {
        IndexResult result = new EndpointIndexer().index(emptyDir);
        assertThat(result.endpoints()).isEmpty();
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
    void selectFormCommand_prefersValidatedCommandOverFirstCandidate(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // 다중 커맨드: 첫 후보(helper)가 아니라 @Valid 붙은 커맨드(cmd)를 폼 커맨드로 선택해야 한다.
        java.nio.file.Path src = dir.resolve("M.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import jakarta.validation.Valid;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                @RequestMapping("/web/multi")
                class M {
                    static class HelperForm { private String note;
                        public String getNote(){return note;} public void setNote(String n){this.note=n;} }
                    static class CmdForm { private Integer quantity;
                        public Integer getQuantity(){return quantity;} public void setQuantity(Integer q){this.quantity=q;} }
                    @PostMapping
                    String submit(HelperForm helper, @Valid CmdForm cmd) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        Endpoint post = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("POST") && e.path().equals("/web/multi"))
                .findFirst().orElseThrow();
        // FORM 커맨드는 정확히 1개, @Valid 붙은 CmdForm 이어야 한다.
        assertThat(post.params()).filteredOn(p -> p.kind() == ParamKind.FORM)
                .extracting(EndpointParam::javaType).containsExactly("x.M$CmdForm");
    }

    @Test
    void selectFormCommand_validatedAnnotationAlsoSelected(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path src = dir.resolve("V.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.validation.annotation.Validated;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                @RequestMapping("/web/v")
                class V {
                    static class A { private String a; public String getA(){return a;} public void setA(String x){this.a=x;} }
                    static class B { private String b; public String getB(){return b;} public void setB(String x){this.b=x;} }
                    @PostMapping
                    String submit(A a, @Validated B b) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint post = result.endpoints().stream()
                .filter(e -> e.path().equals("/web/v")).findFirst().orElseThrow();
        assertThat(post.params()).filteredOn(p -> p.kind() == ParamKind.FORM)
                .extracting(EndpointParam::javaType).containsExactly("x.V$B");
    }

    @Test
    void selectFormCommand_noValidation_fallsBackToFirstCandidate(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path src = dir.resolve("F.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                @RequestMapping("/web/f")
                class F {
                    static class P { private String p; public String getP(){return p;} public void setP(String x){this.p=x;} }
                    static class Q { private String q; public String getQ(){return q;} public void setQ(String x){this.q=x;} }
                    @PostMapping
                    String submit(P p, Q q) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint post = result.endpoints().stream()
                .filter(e -> e.path().equals("/web/f")).findFirst().orElseThrow();
        assertThat(post.params()).filteredOn(p -> p.kind() == ParamKind.FORM)
                .extracting(EndpointParam::javaType).containsExactly("x.F$P");
    }

    @Test
    void formBindingIndex_classifiesNestedPojoFieldAndCollectsNestedShape(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // 커맨드의 컨버터 없는 POJO 필드(Address)는 NESTED, 스칼라(quantity)는 SCALAR로 분류되고,
        // 중첩 타입의 shape가 bodyShapes에 수집돼 런타임 점-경로 평면화에 쓰인다.
        java.nio.file.Path src = dir.resolve("N.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                @RequestMapping("/web/nested")
                class N {
                    static class Address { private String city;
                        public String getCity(){return city;} public void setCity(String c){this.city=c;} }
                    static class Cmd { private Address address; private Integer quantity;
                        public Address getAddress(){return address;} public void setAddress(Address a){this.address=a;}
                        public Integer getQuantity(){return quantity;} public void setQuantity(Integer q){this.quantity=q;} }
                    @PostMapping
                    String submit(Cmd cmd) { return "redirect:/ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint post = result.endpoints().stream()
                .filter(e -> e.path().equals("/web/nested")).findFirst().orElseThrow();
        List<FormFieldBinding> bindings = result.formBindingIndex().get(post.id());
        assertThat(bindings).isNotNull();
        assertThat(bindings).filteredOn(b -> b.field().equals("address"))
                .extracting(FormFieldBinding::kind).containsExactly(FormFieldBinding.Kind.NESTED);
        assertThat(bindings).filteredOn(b -> b.field().equals("quantity"))
                .extracting(FormFieldBinding::kind).containsExactly(FormFieldBinding.Kind.SCALAR);
        assertThat(result.bodyShapes()).containsKey("x.N$Address");
    }

    @Test
    void formBindingIndex_classifiesConvertedTypeAsReference(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // Formatter<Color> 대상 타입(Color)을 필드로 가진 커맨드 → color 필드 REFERENCE(refEntityFqn=Color).
        java.nio.file.Files.writeString(dir.resolve("R.java"), """
                package x;
                import org.springframework.format.Formatter;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                class Color { private String name;
                    public String getName(){return name;} public void setName(String n){this.name=n;} }
                class ColorFormatter implements Formatter<Color> {
                    public Color parse(String t, java.util.Locale l){return new Color();}
                    public String print(Color c, java.util.Locale l){return "";}
                }
                @Controller @RequestMapping("/web/r")
                class R {
                    static class Cmd { private Color color; private Integer quantity;
                        public Color getColor(){return color;} public void setColor(Color c){this.color=c;}
                        public Integer getQuantity(){return quantity;} public void setQuantity(Integer q){this.quantity=q;} }
                    @PostMapping String submit(Cmd cmd){return "redirect:/ok";}
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint post = result.endpoints().stream()
                .filter(e -> e.path().equals("/web/r")).findFirst().orElseThrow();
        List<FormFieldBinding> bindings = result.formBindingIndex().get(post.id());
        assertThat(bindings).filteredOn(b -> b.field().equals("color"))
                .extracting(FormFieldBinding::kind).containsExactly(FormFieldBinding.Kind.REFERENCE);
        assertThat(bindings).filteredOn(b -> b.field().equals("color"))
                .extracting(FormFieldBinding::refEntityFqn).containsExactly("x.Color");
        assertThat(bindings).filteredOn(b -> b.field().equals("quantity"))
                .extracting(FormFieldBinding::kind).containsExactly(FormFieldBinding.Kind.SCALAR);
    }

    @Test
    void formBindingIndex_classifiesEntityWithManyToOneJoinColumnAsReference(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // @Entity 타입은 컨버터 미감지여도 REFERENCE(best-effort) + @ManyToOne @JoinColumn(name)에서 FK 컬럼 추출.
        java.nio.file.Files.writeString(dir.resolve("J.java"), """
                package x;
                import jakarta.persistence.*;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Entity class Pet { @Id Long id; }
                @Controller @RequestMapping("/web/j")
                class J {
                    static class Cmd {
                        @ManyToOne @JoinColumn(name="type_id") private Pet pet;
                        public Pet getPet(){return pet;} public void setPet(Pet p){this.pet=p;} }
                    @PostMapping String submit(Cmd cmd){return "redirect:/ok";}
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint post = result.endpoints().stream()
                .filter(e -> e.path().equals("/web/j")).findFirst().orElseThrow();
        List<FormFieldBinding> bindings = result.formBindingIndex().get(post.id());
        assertThat(bindings).filteredOn(b -> b.field().equals("pet")).allSatisfy(b -> {
            assertThat(b.kind()).isEqualTo(FormFieldBinding.Kind.REFERENCE);
            assertThat(b.refEntityFqn()).isEqualTo("x.Pet");
            assertThat(b.joinColumn()).isEqualTo("type_id");
        });
    }

    @Test
    void formBindingIndex_controllerLocalEditorScopesReferenceToThatControllerOnly(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // @InitBinder registerCustomEditor(Sku.class)를 등록한 컨트롤러 A의 Sku 필드만 REFERENCE,
        // editor 미등록 컨트롤러 B의 동일 Sku 필드는 NESTED(컨트롤러-local 스코프 회귀 가드, spec §5-5).
        java.nio.file.Files.writeString(dir.resolve("E.java"), """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.WebDataBinder;
                import org.springframework.web.bind.annotation.*;
                class Sku { private String code;
                    public String getCode(){return code;} public void setCode(String c){this.code=c;} }
                @Controller @RequestMapping("/web/a")
                class A {
                    @InitBinder void init(WebDataBinder b){
                        b.registerCustomEditor(Sku.class, new java.beans.PropertyEditorSupport()); }
                    static class Cmd { private Sku sku;
                        public Sku getSku(){return sku;} public void setSku(Sku s){this.sku=s;} }
                    @PostMapping String submit(Cmd cmd){return "redirect:/ok";}
                }
                @Controller @RequestMapping("/web/b")
                class B {
                    static class Cmd { private Sku sku;
                        public Sku getSku(){return sku;} public void setSku(Sku s){this.sku=s;} }
                    @PostMapping String submit(Cmd cmd){return "redirect:/ok";}
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint a = result.endpoints().stream()
                .filter(e -> e.path().equals("/web/a")).findFirst().orElseThrow();
        Endpoint b = result.endpoints().stream()
                .filter(e -> e.path().equals("/web/b")).findFirst().orElseThrow();
        assertThat(result.formBindingIndex().get(a.id())).filteredOn(x -> x.field().equals("sku"))
                .extracting(FormFieldBinding::kind).containsExactly(FormFieldBinding.Kind.REFERENCE);
        assertThat(result.formBindingIndex().get(b.id())).filteredOn(x -> x.field().equals("sku"))
                .extracting(FormFieldBinding::kind).containsExactly(FormFieldBinding.Kind.NESTED);
    }

    @Test
    void formBindingIndex_collectionFieldFallsBackToScalar_nonTarget(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // 컬렉션 필드(List<Y>)는 v1 비목표 → 스칼라/skip 폴백(NESTED·REFERENCE 아님).
        java.nio.file.Files.writeString(dir.resolve("C.java"), """
                package x;
                import java.util.List;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                class Item { private String n; public String getN(){return n;} public void setN(String v){this.n=v;} }
                @Controller @RequestMapping("/web/c")
                class C {
                    static class Cmd { private List<Item> items;
                        public List<Item> getItems(){return items;} public void setItems(List<Item> i){this.items=i;} }
                    @PostMapping String submit(Cmd cmd){return "redirect:/ok";}
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint post = result.endpoints().stream()
                .filter(e -> e.path().equals("/web/c")).findFirst().orElseThrow();
        assertThat(result.formBindingIndex().get(post.id())).filteredOn(x -> x.field().equals("items"))
                .extracting(FormFieldBinding::kind).containsExactly(FormFieldBinding.Kind.SCALAR);
    }

    @Test
    void detectsValidRequestBody_onlyForAnnotatedBody(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path src = dir.resolve("S.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import jakarta.validation.Valid;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/sign")
                class S {
                    record Req(String name) {}
                    @PostMapping("/valid")
                    String withValid(@Valid @RequestBody Req req) { return "ok"; }
                    @PostMapping("/plain")
                    String withoutValid(@RequestBody Req req) { return "ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        // @Valid @RequestBody 엔드포인트만 negative-validation 대상으로 surface된다.
        assertThat(result.validBodyEndpointIds()).contains("post-api-sign-valid");
        assertThat(result.validBodyEndpointIds()).doesNotContain("post-api-sign-plain");
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
    void restControllerWithMatchingPathVarNameUnchanged(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // (iv) @RestController에서 @PathVariable 이름이 파라미터명과 일치하면 정규화/역추출이 결과를 바꾸지
        // 않는다(D4 무변): 핸들러가 PATH로 이미 잡으므로 역추출 안 함, 이름도 동일.
        java.nio.file.Path src = dir.resolve("RestById.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/orders")
                class RestById {
                    @GetMapping("/{id}") String get(@PathVariable Long id) { return null; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        Endpoint get = result.endpoints().stream()
                .filter(e -> e.httpMethod().equals("GET")).findFirst().orElseThrow();
        // PATH(id) 정확히 1개 — 역추출로 중복 추가되지 않는다.
        assertThat(get.params()).extracting(EndpointParam::name, EndpointParam::kind)
                .containsExactly(org.assertj.core.api.Assertions.tuple("id", ParamKind.PATH));
        assertThat(get.params().get(0).javaType()).isEqualTo("java.lang.Long");
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

    // ── REQ-017: method-level @RequestMapping(method=…) indexing ──────────────

    @Test
    void req017_methodLevelRequestMappingPostIsIndexed(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // @RequestMapping(value="/x", method=RequestMethod.POST) → POST /x 발견되어야 한다.
        // 기존 verb 어노테이션 루프에서는 @RequestMapping을 처리하지 않아 건너뛰었던 케이스.
        java.nio.file.Path src = dir.resolve("RmPost.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.bind.annotation.RequestMethod;
                @RestController
                class RmPost {
                    @RequestMapping(value = "/x", method = RequestMethod.POST)
                    String create(@RequestBody String body) { return "ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        assertThat(result.endpoints()).hasSize(1);
        Endpoint ep = result.endpoints().get(0);
        assertThat(ep.httpMethod()).isEqualTo("POST");
        assertThat(ep.path()).isEqualTo("/x");
    }

    @Test
    void req017_methodLevelRequestMappingMultiMethodUsesFirst(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // method={RequestMethod.GET, RequestMethod.POST} → 배열의 첫 원소(GET)를 사용한다.
        java.nio.file.Path src = dir.resolve("RmMulti.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.bind.annotation.RequestMethod;
                @RestController
                class RmMulti {
                    @RequestMapping(value = "/multi", method = {RequestMethod.GET, RequestMethod.POST})
                    String handle() { return "ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);

        assertThat(result.endpoints()).hasSize(1);
        Endpoint ep = result.endpoints().get(0);
        assertThat(ep.httpMethod()).isEqualTo("GET");
        assertThat(ep.path()).isEqualTo("/multi");
    }

    @Test
    void req017_methodLevelRequestMappingWithoutMethodAttributeIsSkipped(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // method 속성 없는 메서드-레벨 @RequestMapping(모든 verbs 매치)은 스코프 밖 → skip.
        java.nio.file.Path src = dir.resolve("RmNoMethod.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class RmNoMethod {
                    @RequestMapping("/open")
                    String open() { return "ok"; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        assertThat(result.endpoints()).isEmpty();
    }

    @Test
    void req017_verbAnnotationStillTakesPrecedenceOverRequestMapping(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // @PostMapping이 있으면 기존 루프에서 처리 → @RequestMapping 폴백 코드가 실행되지 않아야 한다(회귀 0).
        java.nio.file.Path src = dir.resolve("RmRegression.java");
        java.nio.file.Files.writeString(src, """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                class RmRegression {
                    @PostMapping("/orders")
                    String create(@RequestBody String b) { return null; }
                    @GetMapping("/orders/{id}")
                    String get(@PathVariable Long id) { return null; }
                }
                """);
        IndexResult result = new EndpointIndexer().index(dir, null);
        assertThat(result.endpoints()).hasSize(2);
        assertThat(result.endpoints()).anyMatch(e -> e.httpMethod().equals("POST") && e.path().equals("/api/orders"));
        assertThat(result.endpoints()).anyMatch(e -> e.httpMethod().equals("GET") && e.path().equals("/api/orders/{id}"));
    }
}
