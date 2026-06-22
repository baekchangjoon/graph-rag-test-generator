package io.graphrag.generator.compose;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-006: outcome=FAILURE path에서는 에러 계약 단언(errorCode equalTo + errorDetail containsString)을
 * 생성해야 한다. outcome=SUCCESS path는 기존 결정성 로직(notNullValue/equalTo)을 유지한다.
 */
class ErrorContractAssertionTest {

    /** FAILURE path + 두 에러 계약 필드 모두 설정됨 → equalTo(str) + containsString 단언 */
    @Test
    void failurePath_emitsErrorCodeEqualToAndDetailContainsString() throws Exception {
        ObjectNode response = Json.mapper().createObjectNode();
        response.put("errorCode", "404");
        response.put("errorDetail", "io.example.BizException: resource not found");

        ExploredPath path = new ExploredPath(
                "p-err-1", "get-api-items-by-id",
                Json.mapper().readTree("{\"id\":\"42\"}"),
                404,
                response,
                List.of(), List.of(), List.of(), "fuzzer",
                List.of(), List.of(), List.of(),
                List.of(), Map.of(),
                Outcome.Kind.FAILURE, 404, "404"
        );

        List<ComposedFixture.Assertion> assertions = new FixtureComposer()
                .compose(path, List.of(), List.of(),
                        "errorCode", "errorDetail", "BizException")
                .assertions();

        // errorCode → equalTo("404") — 문자열 매처 (int가 아님)
        assertThat(assertions)
                .as("errorCode should have equalTo(\"404\") string matcher")
                .anyMatch(a -> a.jsonPath().equals("errorCode")
                        && a.matcher().equals("equalTo(\"404\")"));

        // errorDetail → containsString FQN
        assertThat(assertions)
                .as("errorDetail should have containsString matcher")
                .anyMatch(a -> a.jsonPath().equals("errorDetail")
                        && a.matcher().contains("org.hamcrest.Matchers.containsString(\"BizException\")"));

        // notNullValue-only ではない — equalTo が1件以上あること
        assertThat(assertions)
                .as("FAILURE path must have at least one equalTo matcher, not only notNullValue")
                .anyMatch(a -> a.matcher().startsWith("equalTo"));
    }

    /** FAILURE path + errorDetailField/Contains が null → statusField のみ単언 */
    @Test
    void failurePath_noDetailConfig_emitsOnlyStatusFieldAssertion() throws Exception {
        ObjectNode response = Json.mapper().createObjectNode();
        response.put("errorCode", "400");
        response.put("message", "bad input");

        ExploredPath path = new ExploredPath(
                "p-err-2", "post-api-orders",
                Json.mapper().readTree("{\"userId\":\"u1\"}"),
                400,
                response,
                List.of(), List.of(), List.of(), "fuzzer",
                List.of(), List.of(), List.of(),
                List.of(), Map.of(),
                Outcome.Kind.FAILURE, 400, "400"
        );

        List<ComposedFixture.Assertion> assertions = new FixtureComposer()
                .compose(path, List.of(), List.of(),
                        "errorCode", null, null)
                .assertions();

        // statusField assertion present
        assertThat(assertions)
                .anyMatch(a -> a.jsonPath().equals("errorCode")
                        && a.matcher().equals("equalTo(\"400\")"));

        // no containsString (no detail config)
        assertThat(assertions)
                .noneMatch(a -> a.matcher().contains("containsString"));
    }

    /** SUCCESS path → 기존 결정성 로직 회귀: notNullValue/equalTo 혼용, containsString 없음 */
    @Test
    void successPath_existingDeterminismLogicUnchanged() throws Exception {
        ObjectNode response = Json.mapper().createObjectNode();
        response.put("id", "99");
        response.put("status", "PENDING");

        ExploredPath path = new ExploredPath(
                "p-ok-1", "post-api-orders",
                Json.mapper().readTree("{\"userId\":\"u1\"}"),
                200,
                response,
                List.of(), List.of(), List.of(), "happy",
                List.of(), List.of(), List.of(),
                List.of(), Map.of(),
                Outcome.Kind.SUCCESS, 200, "200"
        );

        List<ComposedFixture.Assertion> assertions = new FixtureComposer()
                .compose(path, List.of(), List.of(),
                        "errorCode", "errorDetail", "BizException")
                .assertions();

        // SUCCESS path: no containsString
        assertThat(assertions)
                .as("SUCCESS path must not emit containsString")
                .noneMatch(a -> a.matcher().contains("containsString"));

        // SUCCESS path: each field uses normal determinism (notNullValue or equalTo)
        assertThat(assertions)
                .as("SUCCESS path must emit at least one assertion per response field")
                .isNotEmpty();

        assertThat(assertions)
                .as("SUCCESS path matchers must all be notNullValue or equalTo style")
                .allMatch(a -> a.matcher().startsWith("notNullValue") || a.matcher().startsWith("equalTo"));
    }
}
