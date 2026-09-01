package io.graphrag.generator.compose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AssertionProvenanceUpgrader 단위 테스트 (REQ-A/B/D — 어설션 provenance 확장).
 *
 * <p>업그레이더는 이미 합성된 assertions 중 matcher가 notNullValue()인 항목만, 증명 가능한
 * 경우에 한해 구체 매처로 승격한다. 새 어설션을 추가하거나 기존 구체 매처를 바꾸지 않는다.
 */
class AssertionProvenanceUpgraderTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ExploredPath path(String endpointId, JsonNode input, int status, JsonNode response) {
        return new ExploredPath(endpointId + "-p1", endpointId, input, status, response,
                List.of(), List.of(), List.of(), "test", List.of(), List.of(), List.of());
    }

    private static Endpoint endpoint(String id, String method, String pathTemplate, List<String> messageLiterals) {
        return new Endpoint(id, method, pathTemplate, "C", "m", List.of(), false, null, messageLiterals);
    }

    private static ComposedFixture.Assertion notNull(String field) {
        return new ComposedFixture.Assertion(field, "notNullValue()");
    }

    // ---------------------------------------------------------------- REQ-A

    @Test
    void envelopeFailure_statusErrorPath_upgradedByFrameworkContract() {
        ExploredPath p = path("post-api-orders", json("{\"amount\":-1}"), 400,
                json("{\"timestamp\":\"2026-09-01T03:29:01.419+00:00\",\"status\":400,"
                        + "\"error\":\"Bad Request\",\"path\":\"/api/orders\"}"));
        Endpoint e = endpoint("post-api-orders", "POST", "/api/orders", List.of());

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("timestamp"), notNull("status"), notNull("error"), notNull("path")),
                p, e, "/api/orders");

        assertThat(out).containsExactly(
                notNull("timestamp"),
                new ComposedFixture.Assertion("status", "equalTo(400)"),
                new ComposedFixture.Assertion("error", "equalTo(\"Bad Request\")"),
                new ComposedFixture.Assertion("path", "equalTo(\"/api/orders\")"));
    }

    @Test
    void envelopeFailure_templatedPath_usesGenerationTimeResolvedPath() {
        // 탐색 시 관측된 path(/api/bookings/12345)와 생성 시 시드 id(/api/bookings/91567)가 달라도
        // 템플릿 세그먼트 일치가 계약을 증명하므로 생성 시점 경로로 equalTo 한다.
        ExploredPath p = path("put-api-bookings-id", json("{\"nights\":0}"), 422,
                json("{\"timestamp\":\"t\",\"status\":422,\"error\":\"Unprocessable Entity\","
                        + "\"path\":\"/api/bookings/12345\"}"));
        Endpoint e = endpoint("put-api-bookings-id", "PUT", "/api/bookings/{id}", List.of());

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("status"), notNull("error"), notNull("path")),
                p, e, "/api/bookings/91567");

        assertThat(out).containsExactly(
                new ComposedFixture.Assertion("status", "equalTo(422)"),
                new ComposedFixture.Assertion("error", "equalTo(\"Unprocessable Entity\")"),
                new ComposedFixture.Assertion("path", "equalTo(\"/api/bookings/91567\")"));
    }

    @Test
    void envelopeFailure_queryStringStripped_fromPathAssertion() {
        ExploredPath p = path("delete-api-bookings-id", json("{}"), 400,
                json("{\"timestamp\":\"t\",\"status\":400,\"error\":\"Bad Request\","
                        + "\"path\":\"/api/bookings/7\"}"));
        Endpoint e = endpoint("delete-api-bookings-id", "DELETE", "/api/bookings/{id}", List.of());

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("path")), p, e, "/api/bookings/91570?confirm=true");

        assertThat(out).containsExactly(
                new ComposedFixture.Assertion("path", "equalTo(\"/api/bookings/91570\")"));
    }

    @Test
    void customErrorValue_orTemplateMismatch_staysNotNull() {
        // error 값이 표준 reason phrase가 아니고, 관측 path가 템플릿과 불일치 → 계약 미증명, 강등 유지.
        ExploredPath p = path("post-api-x", json("{}"), 400,
                json("{\"timestamp\":\"t\",\"status\":400,\"error\":\"Oops\",\"path\":\"/other/route\"}"));
        Endpoint e = endpoint("post-api-x", "POST", "/api/x", List.of());

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("status"), notNull("error"), notNull("path")), p, e, "/api/x");

        assertThat(out).containsExactly(
                new ComposedFixture.Assertion("status", "equalTo(400)"),
                notNull("error"),
                notNull("path"));
    }

    @Test
    void successOutcome_envelopeRulesNotApplied() {
        // 2xx SUCCESS의 status 필드는 도메인 값(예: "PENDING")일 수 있다 — REQ-A는 FAILURE 전용.
        ExploredPath p = path("post-api-orders", json("{\"amount\":1}"), 201,
                json("{\"id\":5,\"status\":\"PENDING\"}"));
        Endpoint e = endpoint("post-api-orders", "POST", "/api/orders", List.of());

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("status")), p, e, "/api/orders");

        assertThat(out).containsExactly(notNull("status"));
    }

    @Test
    void envelopeShapeIncomplete_rulesNotApplied() {
        // 4필드(timestamp/status/error/path)가 다 있어야 Spring 기본 엔벨로프로 인정한다.
        ExploredPath p = path("post-api-x", json("{}"), 400,
                json("{\"status\":400,\"error\":\"Bad Request\"}"));
        Endpoint e = endpoint("post-api-x", "POST", "/api/x", List.of());

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("status"), notNull("error")), p, e, "/api/x");

        assertThat(out).containsExactly(notNull("status"), notNull("error"));
    }

    // ---------------------------------------------------------------- REQ-B (연기됨)

    @Test
    void arrayInput_countField_notUpgraded_reqBDeferred() {
        // REQ-B 연기: count는 입력뿐 아니라 DB 상태(참조 행 존재)에도 의존한다 — 라이브 반례에서
        // 탐색 관측값(1)과 시드 없는 런타임 값(0)이 갈렸다. 강등 유지가 맞다.
        ExploredPath p = path("post-api-orders-batch",
                json("[{\"a\":1},{\"a\":2}]"), 201, json("{\"created\":2}"));
        Endpoint e = endpoint("post-api-orders-batch", "POST", "/api/orders/batch", List.of());

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("created")), p, e, "/api/orders/batch");

        assertThat(out).containsExactly(notNull("created"));
    }

    // ---------------------------------------------------------------- REQ-D

    @Test
    void exposedMessage_exactLiteralMatch_upgradedToEqualTo() {
        ExploredPath p = path("post-api-bookings", json("{\"nights\":0}"), 422,
                json("{\"timestamp\":\"t\",\"status\":422,\"error\":\"Unprocessable Entity\","
                        + "\"path\":\"/api/bookings\",\"message\":\"nights must be between 1 and 30\"}"));
        Endpoint e = endpoint("post-api-bookings", "POST", "/api/bookings",
                List.of("nights must be between 1 and 30", "tier is required"));

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("message")), p, e, "/api/bookings");

        assertThat(out).containsExactly(new ComposedFixture.Assertion(
                "message", "equalTo(\"nights must be between 1 and 30\")"));
    }

    @Test
    void exposedMessage_fragmentContainment_upgradedToContainsString() {
        // 소스가 "booking " + id + " not found"처럼 연결식이면 조각(≥8자) 포함으로 증명한다.
        ExploredPath p = path("get-api-bookings-id", json("{}"), 404,
                json("{\"timestamp\":\"t\",\"status\":404,\"error\":\"Not Found\","
                        + "\"path\":\"/api/bookings/999\",\"message\":\"booking 999 not found\"}"));
        Endpoint e = endpoint("get-api-bookings-id", "GET", "/api/bookings/{id}",
                List.of(" not found"));

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("message")), p, e, "/api/bookings/1");

        assertThat(out).containsExactly(new ComposedFixture.Assertion(
                "message", "org.hamcrest.Matchers.containsString(\" not found\")"));
    }

    @Test
    void requestMutated_messageSkipped_envelopeStillUpgraded() {
        // 404 read의 부재-id 센티널 치환처럼 생성이 요청을 변형한 path에서는, 런타임 arm이 탐색
        // 관측과 다를 수 있어 message 승격을 건너뛴다. arm 무관 계약(status/error)은 유지.
        ExploredPath p = path("get-api-bookings-id", json("{}"), 404,
                json("{\"timestamp\":\"t\",\"status\":404,\"error\":\"Not Found\","
                        + "\"path\":\"/api/bookings/95278\",\"message\":\"booking 95278 is stale\"}"));
        Endpoint e = endpoint("get-api-bookings-id", "GET", "/api/bookings/{id}",
                List.of(" is stale"));

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("status"), notNull("error"), notNull("message")),
                p, e, "/api/bookings/2000000000", true);

        assertThat(out).containsExactly(
                new ComposedFixture.Assertion("status", "equalTo(404)"),
                new ComposedFixture.Assertion("error", "equalTo(\"Not Found\")"),
                notNull("message"));
    }

    @Test
    void exposedMessage_noLiteralEvidence_staysNotNull() {
        ExploredPath p = path("post-api-bookings", json("{}"), 422,
                json("{\"timestamp\":\"t\",\"status\":422,\"error\":\"Unprocessable Entity\","
                        + "\"path\":\"/api/bookings\",\"message\":\"totally dynamic text\"}"));
        Endpoint e = endpoint("post-api-bookings", "POST", "/api/bookings",
                List.of("nights must be between 1 and 30"));

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("message")), p, e, "/api/bookings");

        assertThat(out).containsExactly(notNull("message"));
    }

    // ---------------------------------------------------------------- 공통 불변

    @Test
    void concreteMatchers_neverTouched_andNoAssertionsAdded() {
        ExploredPath p = path("post-api-orders", json("{}"), 400,
                json("{\"timestamp\":\"t\",\"status\":400,\"error\":\"Bad Request\",\"path\":\"/api/orders\"}"));
        Endpoint e = endpoint("post-api-orders", "POST", "/api/orders", List.of());
        ComposedFixture.Assertion existing = new ComposedFixture.Assertion("status", "equalTo(999)");

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(existing), p, e, "/api/orders");

        assertThat(out).containsExactly(existing);
    }

    @Test
    void nullSampleResponse_returnsInputUnchanged() {
        ExploredPath p = path("post-api-orders", json("{}"), 400, null);
        Endpoint e = endpoint("post-api-orders", "POST", "/api/orders", List.of());

        List<ComposedFixture.Assertion> out = AssertionProvenanceUpgrader.upgrade(
                List.of(notNull("status")), p, e, "/api/orders");

        assertThat(out).containsExactly(notNull("status"));
    }
}
