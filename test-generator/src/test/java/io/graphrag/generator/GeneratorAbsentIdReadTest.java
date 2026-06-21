package io.graphrag.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fix#1 (PR #62 flaky): absent-id read(404) 시나리오의 numeric path id 를 IDENTITY 가 한 테스트 런에서
 * 도달 불가능한 큰 값으로 치환한다. 캡처된 작은 probe id(예: 1)를 그대로 쓰면, 같은 공유 SUT DB 에 대해
 * 병렬로 도는 성공 POST 가 IDENTITY id=1 을 만들어 404 가 200 으로 뒤집히는 race 가 난다.
 */
class GeneratorAbsentIdReadTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Endpoint getById(String javaType) {
        return new Endpoint("get-api-bookings-id", "GET", "/api/bookings/{id}",
                "io.sample.BookingController", "getById",
                List.of(new EndpointParam("id", javaType, ParamKind.PATH)), false);
    }

    private static JsonNode input(String json) {
        try { return M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void notFoundRead_numericPathId_usesUnreachableAbsentId() {
        Endpoint ep = getById("java.lang.Long");
        // 캡처된 probe id=1 이지만 404 read 라 도달불가 큰 id 로 치환되어야 한다.
        String path = Generator.resolveLiteralPath(ep, input("{\"id\":1}"), true);
        assertThat(path).isEqualTo("/api/bookings/2000000000");
    }

    @Test
    void notFoundRead_intPathId_alsoSubstituted() {
        Endpoint ep = getById("int");
        String path = Generator.resolveLiteralPath(ep, input("{\"id\":3}"), true);
        assertThat(path).isEqualTo("/api/bookings/2000000000");
    }

    @Test
    void successRead_keepsCapturedId() {
        Endpoint ep = getById("java.lang.Long");
        // 2xx read 는 시드한 자기 데이터를 조회하므로 캡처 id 를 그대로 유지해야 한다.
        String path = Generator.resolveLiteralPath(ep, input("{\"id\":95277}"), false);
        assertThat(path).isEqualTo("/api/bookings/95277");
    }

    @Test
    void notFoundRead_nonNumericPathId_unchanged() {
        Endpoint ep = getById("java.lang.String");
        // 문자열 id 는 IDENTITY race 대상이 아니므로 캡처값 유지(치환 안 함).
        String path = Generator.resolveLiteralPath(ep, input("{\"id\":\"abc\"}"), true);
        assertThat(path).isEqualTo("/api/bookings/abc");
    }

    @Test
    void trailingDoubleWildcardConcretizedToProbeSegment() {
        // 게이트웨이 predicate path: /api/v1/orders/** → /api/v1/orders/probe
        Endpoint ep = new Endpoint("gw-orders", "GET", "/api/v1/orders/**",
                "io.sample.GatewayController", "forwardOrders", List.of(), false);
        String path = Generator.resolveLiteralPath(ep, M.createObjectNode(), false);
        assertThat(path).isEqualTo("/api/v1/orders/probe");
        assertThat(path).doesNotContain("**");
    }

    @Test
    void trailingSingleWildcardConcretizedToProbeSegment() {
        Endpoint ep = new Endpoint("gw-items", "GET", "/api/v1/items/*",
                "io.sample.GatewayController", "forwardItems", List.of(), false);
        String path = Generator.resolveLiteralPath(ep, M.createObjectNode(), false);
        assertThat(path).isEqualTo("/api/v1/items/probe");
        assertThat(path).doesNotContain("*");
    }

    @Test
    void midPathWildcardConcretized() {
        // 중간 세그먼트 wildcard: /a/*/b → /a/probe/b
        Endpoint ep = new Endpoint("gw-mid", "GET", "/a/*/b",
                "io.sample.GatewayController", "forwardMid", List.of(), false);
        String path = Generator.resolveLiteralPath(ep, M.createObjectNode(), false);
        assertThat(path).isEqualTo("/a/probe/b");
    }

    @Test
    void nonWildcardPathUnchangedByWildcardNormalization() {
        // 회귀: 일반 captured id 경로는 wildcard 치환 영향 없음
        Endpoint ep = getById("java.lang.Long");
        String path = Generator.resolveLiteralPath(ep, input("{\"id\":42}"), false);
        assertThat(path).isEqualTo("/api/bookings/42");
    }

    // ── REQ-018: empty path-var → sentinel (capture/reproduce parity) ──────────

    @Test
    void req018_emptyStringPathVarUsesSentinelNotDoubleSlash() {
        // input.id="" → path.replace("{id}", "") → double-slash 방지.
        // Generator.resolveLiteralPath: 빈 문자열 입력도 sentinel 사용(parity).
        Endpoint ep = new Endpoint("get-x-id-content", "GET", "/x/{id}/content",
                "io.sample.C", "h",
                List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);
        String path = Generator.resolveLiteralPath(ep, input("{\"id\":\"\"}"), false);
        assertThat(path).doesNotContain("//");
        assertThat(path).isEqualTo("/x/0/content");
    }

    @Test
    void req018_emptyStringPathVarEquivalentToMissingPathVar() {
        // 빈 문자열과 필드 누락이 동일한 sentinel 경로를 생성해야 한다(parity).
        Endpoint ep = new Endpoint("get-x-id-content", "GET", "/x/{id}/content",
                "io.sample.C", "h",
                List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);
        String emptyPath = Generator.resolveLiteralPath(ep, input("{\"id\":\"\"}"), false);
        String missingPath = Generator.resolveLiteralPath(ep, M.createObjectNode(), false);
        assertThat(emptyPath).isEqualTo(missingPath);
    }

    @Test
    void req018_whitespaceOnlyPathVarUsesSentinel() {
        // 공백만 있는 값도 blank로 취급해 sentinel 사용.
        Endpoint ep = new Endpoint("get-a-x-b", "GET", "/a/{x}/b",
                "io.sample.C", "h",
                List.of(new EndpointParam("x", "java.lang.String", ParamKind.PATH)), false);
        String path = Generator.resolveLiteralPath(ep, input("{\"x\":\"   \"}"), false);
        assertThat(path).doesNotContain("//");
        assertThat(path).doesNotContain("   ");
    }

    @Test
    void req018_nonEmptyPathVarUnchanged() {
        // 회귀: 비어있지 않은 값은 그대로 사용해야 한다.
        Endpoint ep = getById("java.lang.Long");
        String path = Generator.resolveLiteralPath(ep, input("{\"id\":\"99\"}"), false);
        assertThat(path).isEqualTo("/api/bookings/99");
    }

    @Test
    void req018_emptyStringPathVarSentinelMatchesBuilderSentinel() {
        // capture/reproduce parity 계약 검증: Generator(reproduce)에서 빈 문자열 id의 sentinel이
        // EndpointExplorationRunner(capture)의 sentinel과 동일하다.
        // 두 모듈은 동일한 sentinel 값("0" for Long, "missing" for String)을 사용해야 한다.
        // 모듈 경계로 직접 교차 호출은 불가하지만, 두 쪽 모두 동일 sentinel을 생성함을 각자 검증.
        // (EndpointExplorationRunnerUrlTest의 req018_emptyStringPathVarUsesSentinelNotDoubleSlash
        //  도 동일한 "/x/0/content"를 assert → 양쪽 sentinel "0"이 일치.)
        Endpoint ep = new Endpoint("get-api-bookings-id", "GET", "/api/bookings/{id}",
                "io.sample.BookingController", "getById",
                List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);
        String generatorPath = Generator.resolveLiteralPath(ep, input("{\"id\":\"\"}"), false);
        // 기대값: Explorer도 "0"(sentinel for Long, non-notFoundRead) → "/api/bookings/0"
        assertThat(generatorPath).isEqualTo("/api/bookings/0");
    }
}
