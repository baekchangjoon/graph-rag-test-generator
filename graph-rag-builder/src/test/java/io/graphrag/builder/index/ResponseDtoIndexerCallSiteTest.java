package io.graphrag.builder.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import spoon.reflect.CtModel;

/** REQ-004, REQ-005: ResponseDtoIndexer.extractCallSites — (method, pathLiteral, responseShape). */
class ResponseDtoIndexerCallSiteTest {

    private static CtModel model(String src) {
        return model(new String[] {src}, new String[] {"In.java"});
    }

    private static CtModel model(String[] srcs, String[] names) {
        spoon.Launcher l = new spoon.Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        for (int i = 0; i < srcs.length; i++) {
            l.addInputResource(new spoon.support.compiler.VirtualFile(srcs[i], names[i]));
        }
        return l.buildModel();
    }

    private static ExternalCallSite byPath(List<ExternalCallSite> sites, String path) {
        return sites.stream().filter(s -> s.pathLiteral().equals(path)).findFirst().orElseThrow();
    }

    private static final String REST = "class RestTemplate {"
            + " <T> T getForObject(String u, Class<T> c){ return null; }"
            + " <T> T postForObject(String u, Object b, Class<T> c){ return null; }"
            + " <T> T getForEntity(String u, Class<T> c){ return null; }"
            + " <T> T exchange(String u, org.springframework.http.HttpMethod m,"
            + " Object e, Class<T> c){ return null; } }";

    private static final String HTTP_METHOD =
            "package org.springframework.http; public enum HttpMethod { GET, POST, PUT, DELETE }";

    @Test
    void extractsGetMethodPathAndShape() {
        CtModel m = model(
                "package p;"
                + " record InventoryResponse(Integer available) {}"
                + " class Client { " + REST + ".class T; RestTemplate rest = new RestTemplate();"
                + " String baseUrl = \"http://x\";"
                + " Object check(String type) {"
                + "   return rest.getForObject(baseUrl + \"/inventory/stock?type=\" + type,"
                + "       InventoryResponse.class); } }");

        List<ExternalCallSite> sites = new ResponseDtoIndexer().extractCallSites(m);

        ExternalCallSite inv = byPath(sites, "/inventory/stock");
        assertThat(inv.httpMethod()).isEqualTo("GET");
        assertThat(inv.responseShape()).isPresent();
        assertThat(inv.responseShape().get().fields()).extracting(BodyShape.BodyField::name)
                .containsExactly("available");
        assertThat(inv.responseShape().get().collection()).isFalse();
    }

    @Test
    void postMapsToPostMethod() {
        CtModel m = model(
                "package p;"
                + " record Receipt(String id) {}"
                + " class C { RestTemplate rest = new RestTemplate(); String b=\"\";"
                + " Object pay() { return rest.postForObject(b + \"/payments\", null, Receipt.class); } }"
                + REST);

        List<ExternalCallSite> sites = new ResponseDtoIndexer().extractCallSites(m);

        ExternalCallSite pay = byPath(sites, "/payments");
        assertThat(pay.httpMethod()).isEqualTo("POST");
        assertThat(pay.responseShape()).isPresent();
    }

    @Test
    void arrayResponseTypeIsCollectionComponentShape() {
        CtModel m = model(
                "package p;"
                + " record Item(String sku) {}"
                + " class C { RestTemplate rest = new RestTemplate(); String b=\"\";"
                + " Object list() { return rest.getForObject(b + \"/items\", Item[].class); } }"
                + REST);

        List<ExternalCallSite> sites = new ResponseDtoIndexer().extractCallSites(m);

        ExternalCallSite items = byPath(sites, "/items");
        assertThat(items.httpMethod()).isEqualTo("GET");
        assertThat(items.responseShape()).isPresent();
        assertThat(items.responseShape().get().collection()).isTrue();
        assertThat(items.responseShape().get().javaType()).isEqualTo("p.Item");
        assertThat(items.responseShape().get().fields()).extracting(BodyShape.BodyField::name)
                .containsExactly("sku");
    }

    @Test
    void exchangeWithHttpMethodConstantExtractsMethod() {
        CtModel m = model(
                new String[] {
                    HTTP_METHOD,
                    "package p;"
                    + " record R(String x) {}"
                    + " class C { RestTemplate rest = new RestTemplate(); String b=\"\";"
                    + " Object go() { return rest.exchange(b + \"/ex\","
                    + "   org.springframework.http.HttpMethod.POST, null, R.class); } }"
                    + REST},
                new String[] {"HttpMethod.java", "In.java"});

        List<ExternalCallSite> sites = new ResponseDtoIndexer().extractCallSites(m);

        ExternalCallSite ex = byPath(sites, "/ex");
        assertThat(ex.httpMethod()).isEqualTo("POST");
        assertThat(ex.responseShape()).isPresent();
    }

    @Test
    void exchangeWithVariableMethodArgYieldsNoExtractableMethod() {
        // HttpMethod 인자가 변수 참조 → method 추출 불가. site는 path 기준으로 남되 httpMethod=빈 문자열.
        CtModel m = model(
                new String[] {
                    HTTP_METHOD,
                    "package p;"
                    + " record R(String x) {}"
                    + " class C { RestTemplate rest = new RestTemplate(); String b=\"\";"
                    + " Object go(org.springframework.http.HttpMethod mm) {"
                    + "   return rest.exchange(b + \"/exvar\", mm, null, R.class); } }"
                    + REST},
                new String[] {"HttpMethod.java", "In.java"});

        List<ExternalCallSite> sites = new ResponseDtoIndexer().extractCallSites(m);

        ExternalCallSite ex = byPath(sites, "/exvar");
        assertThat(ex.httpMethod()).isEmpty();
    }

    @Test
    void unextractableResponseTypeYieldsEmptyShape() {
        // 응답 타입이 class 리터럴이 아니라 변수 참조(ParameterizedTypeReference 류) → shape empty.
        CtModel m = model(
                new String[] {
                    HTTP_METHOD,
                    "package p;"
                    + " class PTR<T> {}"
                    + " class C { RestTemplate rest = new RestTemplate(); String b=\"\";"
                    + " PTR<Object> ref = new PTR<>();"
                    + " Object go() { return rest.exchange(b + \"/generic\","
                    + "   org.springframework.http.HttpMethod.GET, null, ref); } }"
                    + REST},
                new String[] {"HttpMethod.java", "In.java"});

        List<ExternalCallSite> sites = new ResponseDtoIndexer().extractCallSites(m);

        ExternalCallSite g = byPath(sites, "/generic");
        assertThat(g.responseShape()).isEmpty();
    }
}
