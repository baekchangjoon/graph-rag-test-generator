package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** buildPathAndQuery/formEncode 단위 가드 — 폼 인덱싱이 노출한 URL/인코딩 경로. */
class EndpointExplorationRunnerUrlTest {

    private static Endpoint endpoint(String path, EndpointParam... params) {
        return new Endpoint("id", "POST", path, "C", "m", List.of(params), false);
    }

    @Test
    void leftoverClassLevelPathPlaceholderReplacedWithSentinel() {
        // petclinic @Controller 폼: @RequestMapping("/owners/{ownerId}")가 @ModelAttribute 헬퍼에서만
        // 해석되어 핸들러 파라미터에 ownerId(PATH)가 없다. 치환 안 된 {ownerId}가 남으면 URI.create가
        // 깨졌었다(IllegalArgumentException) → 센티널("0")로 치환해 URL을 항상 유효하게 만든다.
        Endpoint ep = endpoint("/owners/{ownerId}/pets/new");   // PATH 파라미터 없음
        String url = EndpointExplorationRunner.buildPathAndQuery(ep, Json.mapper().createObjectNode());
        assertThat(url).isEqualTo("/owners/0/pets/new");
        assertThat(url).doesNotContain("{");
    }

    @Test
    void pathParamSubstitutedFromInputAndQueryAppended() {
        Endpoint ep = endpoint("/api/orders/{id}",
                new EndpointParam("id", "java.lang.Long", ParamKind.PATH),
                new EndpointParam("type", "java.lang.String", ParamKind.QUERY));
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("id", 42);
        input.put("type", "EXPRESS");
        assertThat(EndpointExplorationRunner.buildPathAndQuery(ep, input))
                .isEqualTo("/api/orders/42?type=EXPRESS");
    }

    @Test
    void missingPathParamUsesTypedSentinelNotLiteralBraces() {
        Endpoint ep = endpoint("/api/orders/{id}",
                new EndpointParam("id", "java.lang.Long", ParamKind.PATH));
        // 변이가 id를 지워도 URL은 유효해야 한다(숫자형 → "0").
        String url = EndpointExplorationRunner.buildPathAndQuery(ep, Json.mapper().createObjectNode());
        assertThat(url).isEqualTo("/api/orders/0");
    }

    @Test
    void trailingDoubleWildcardConcretizedToProbeSegment() {
        // 게이트웨이 predicate path: /api/v1/orders/** → /api/v1/orders/probe
        Endpoint ep = endpoint("/api/v1/orders/**");
        String url = EndpointExplorationRunner.buildPathAndQuery(ep, Json.mapper().createObjectNode());
        assertThat(url).isEqualTo("/api/v1/orders/probe");
        assertThat(url).doesNotContain("**");
    }

    @Test
    void trailingSingleWildcardConcretizedToProbeSegment() {
        // 단일 * wildcard도 probe로 치환
        Endpoint ep = endpoint("/api/v1/items/*");
        String url = EndpointExplorationRunner.buildPathAndQuery(ep, Json.mapper().createObjectNode());
        assertThat(url).isEqualTo("/api/v1/items/probe");
        assertThat(url).doesNotContain("*");
    }

    @Test
    void midPathWildcardConcretized() {
        // 중간 세그먼트 wildcard: /a/*/b → /a/probe/b
        Endpoint ep = endpoint("/a/*/b");
        String url = EndpointExplorationRunner.buildPathAndQuery(ep, Json.mapper().createObjectNode());
        assertThat(url).isEqualTo("/a/probe/b");
    }

    @Test
    void nonWildcardPathUnchangedByWildcardNormalization() {
        // 회귀: 일반 path param({id})은 wildcard 치환 영향 없음
        Endpoint ep = endpoint("/api/orders/{id}",
                new EndpointParam("id", "java.lang.Long", ParamKind.PATH));
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("id", 7);
        assertThat(EndpointExplorationRunner.buildPathAndQuery(ep, input))
                .isEqualTo("/api/orders/7");
    }

    // ── REQ-018: empty path-var → sentinel (capture/reproduce parity) ──────────

    @Test
    void req018_emptyStringPathVarUsesSentinelNotDoubleSlash() {
        // input.id="" → path.replace("{id}", "") → "/x//content" (double-slash) 방지.
        // 빈 문자열 입력은 누락과 동일하게 취급해 sentinel을 사용해야 한다.
        Endpoint ep = endpoint("/x/{id}/content",
                new EndpointParam("id", "java.lang.Long", ParamKind.PATH));
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("id", "");
        String url = EndpointExplorationRunner.buildPathAndQuery(ep, input);
        assertThat(url).doesNotContain("//");
        assertThat(url).isEqualTo("/x/0/content");
    }

    @Test
    void req018_emptyStringPathVarEquivalentToMissingPathVar() {
        // 빈 문자열과 필드 누락 모두 동일한 sentinel 경로를 생성해야 한다(parity).
        Endpoint ep = endpoint("/x/{id}/content",
                new EndpointParam("id", "java.lang.Long", ParamKind.PATH));
        ObjectNode emptyInput = Json.mapper().createObjectNode();
        emptyInput.put("id", "");
        ObjectNode missingInput = Json.mapper().createObjectNode();
        assertThat(EndpointExplorationRunner.buildPathAndQuery(ep, emptyInput))
                .isEqualTo(EndpointExplorationRunner.buildPathAndQuery(ep, missingInput));
    }

    @Test
    void req018_whitespaceOnlyPathVarUsesSentinel() {
        // 공백만 있는 값도 blank로 취급해 sentinel 사용.
        Endpoint ep = endpoint("/a/{x}/b",
                new EndpointParam("x", "java.lang.String", ParamKind.PATH));
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("x", "   ");
        String url = EndpointExplorationRunner.buildPathAndQuery(ep, input);
        assertThat(url).doesNotContain("//");
        assertThat(url).doesNotContain("   ");
    }

    @Test
    void req018_nonEmptyPathVarUnchanged() {
        // 회귀: 비어있지 않은 값은 그대로 사용해야 한다.
        Endpoint ep = endpoint("/x/{id}/content",
                new EndpointParam("id", "java.lang.Long", ParamKind.PATH));
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("id", 42);
        assertThat(EndpointExplorationRunner.buildPathAndQuery(ep, input))
                .isEqualTo("/x/42/content");
    }

    @Test
    void formEncodeProducesUrlEncodedFlatScalarsSkippingNullAndContainers() {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("customer", "a b");          // 공백 → %20 인코딩
        body.put("quantity", 5);
        body.putNull("note");                   // null → 제외
        body.putObject("nested");               // 중첩 → 제외
        String encoded = EndpointExplorationRunner.formEncode(body);
        assertThat(encoded).isEqualTo("customer=a+b&quantity=5");
    }
}
