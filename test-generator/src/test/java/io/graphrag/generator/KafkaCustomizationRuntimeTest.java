package io.graphrag.generator;

import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.comparator.CustomComparator;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-010/011 버그 재현 + 수정 확인:
 * JSONAssert Customization의 ValueMatcher.equal(o1, o2)에서 인자 순서를 실증적으로 검증한다.
 *
 * <p>JSONAssert 1.5.1, CustomComparator.compareValues(prefix, actual, expected, result) 호출 시
 * Customization.matches(prefix, actual, expected, result)로 전달되며,
 * ValueMatcher.equal(o1, o2)에서 <b>o1 = actual, o2 = expected(captured literal)</b> 임을 확인한다.
 */
class KafkaCustomizationRuntimeTest {

    // ── Step 1: 인자 순서 실증적 결정 ─────────────────────────────────────────

    @Test
    void jsonAssert_customization_o1isActual_o2isExpected() throws Exception {
        // expected JSON에는 "CAPTURED", actual JSON에는 "RUNTIME"을 넣어
        // ValueMatcher 호출 시 어느 인자가 "RUNTIME"(= actual)인지 기록한다.
        AtomicReference<String> capturedO1 = new AtomicReference<>();
        AtomicReference<String> capturedO2 = new AtomicReference<>();

        JSONAssert.assertEquals(
            "{\"userId\":\"CAPTURED\"}",          // expected (captured literal)
            "{\"userId\":\"RUNTIME\"}",             // actual   (what the SUT emitted)
            new CustomComparator(JSONCompareMode.LENIENT,
                new Customization("userId", (o1, o2) -> {
                    capturedO1.set(o1 == null ? "NULL" : o1.toString());
                    capturedO2.set(o2 == null ? "NULL" : o2.toString());
                    return true; // 인자 기록 후 항상 통과
                }))
        );

        assertThat(capturedO1.get())
            .as("o1 must be the ACTUAL value emitted by SUT")
            .isEqualTo("RUNTIME");
        assertThat(capturedO2.get())
            .as("o2 must be the EXPECTED (captured literal) value")
            .isEqualTo("CAPTURED");
    }

    // ── Step 2: 버그 재현 (o2 사용 → 항상 실패) ──────────────────────────────

    @Test
    void bugRepro_substitutionUsesO2_alwaysFails() {
        // 생성된 테스트에서 userId를 substitution 변수 "t-xxxx-user"로 교체한다.
        // 버그 버전: o2(= captured "probe-userId")와 비교 → o2는 절대 "t-xxxx-user"가 아님.
        String userId = "t-xxxx-user"; // 실제 테스트가 send하는 값

        assertThatThrownBy(() ->
            JSONAssert.assertEquals(
                "{\"userId\":\"probe-userId\"}",   // expected = captured literal
                "{\"userId\":\"t-xxxx-user\"}",    // actual   = SUT emitted (= input variable)
                new CustomComparator(JSONCompareMode.LENIENT,
                    new Customization("userId",
                        // BUG: o2 = expected("probe-userId"), never equals userId("t-xxxx-user")
                        (o1, o2) -> Objects.equals(o2 == null ? null : o2.toString(), String.valueOf(userId)))))
        )
        .as("Bug: o2-based substitution always fails because o2 is the captured literal, not the actual")
        .isInstanceOf(AssertionError.class);
    }

    @Test
    void bugRepro_serverGeneratedUsesO2_vacuouslyPassesOnLiteral() throws Exception {
        // 버그 버전: o2(= captured UUID literal)에 regex를 적용.
        // captured literal("550e8400-...")은 UUID regex에 이미 맞으므로 항상 통과 → 실제 검증 없음.
        String capturedUuid = "550e8400-e29b-41d4-a716-446655440000";
        String notAUuid     = "not-a-uuid";
        String uuidRegex    = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

        // Bug: actual이 UUID가 아니어도 o2(=captured UUID)가 regex를 통과시켜 버림
        JSONAssert.assertEquals(
            "{\"eventId\":\"" + capturedUuid + "\"}",
            "{\"eventId\":\"" + notAUuid + "\"}",       // actual은 UUID가 아닌데도 통과
            new CustomComparator(JSONCompareMode.LENIENT,
                new Customization("eventId",
                    // BUG: o2 = expected("550e8400-..."), matches UUID regex vacuously
                    (o1, o2) -> o2 != null && o2.toString().matches(uuidRegex)))
        );
        // → AssertionError 없이 통과: 버그. actual("not-a-uuid")를 검사하지 않음.
    }

    // ── Step 3: 수정 버전 (o1 사용 → 올바르게 동작) ─────────────────────────

    @Test
    void fix_substitutionUsesO1_passesWhenActualMatchesVariable() throws Exception {
        String userId = "t-xxxx-user";

        // FIX: o1 = actual("t-xxxx-user") → String.valueOf(userId)와 비교 → 통과
        JSONAssert.assertEquals(
            "{\"userId\":\"probe-userId\"}",
            "{\"userId\":\"t-xxxx-user\"}",
            new CustomComparator(JSONCompareMode.LENIENT,
                new Customization("userId",
                    (o1, o2) -> Objects.equals(o1 == null ? null : o1.toString(), String.valueOf(userId))))
        );
        // 예외 없이 통과 = 수정 올바름
    }

    @Test
    void fix_serverGeneratedUsesO1_failsWhenActualNotUuid() {
        String capturedUuid = "550e8400-e29b-41d4-a716-446655440000";
        String notAUuid     = "not-a-uuid";
        String uuidRegex    = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

        // FIX: o1 = actual("not-a-uuid") → matches(uuidRegex) → false → AssertionError
        assertThatThrownBy(() ->
            JSONAssert.assertEquals(
                "{\"eventId\":\"" + capturedUuid + "\"}",
                "{\"eventId\":\"" + notAUuid + "\"}",
                new CustomComparator(JSONCompareMode.LENIENT,
                    new Customization("eventId",
                        (o1, o2) -> o1 != null && o1.toString().matches(uuidRegex))))
        )
        .as("Fix: o1-based server-gen check rejects non-UUID actual values")
        .isInstanceOf(AssertionError.class);
    }

    @Test
    void fix_serverGeneratedUsesO1_passesWhenActualIsUuid() throws Exception {
        String capturedUuid = "550e8400-e29b-41d4-a716-446655440000";
        String actualUuid   = "aaaabbbb-cccc-dddd-eeee-ffffaaaabbbb"; // different from captured
        String uuidRegex    = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

        // FIX: actual is a real UUID (different from captured) → o1 matches regex → 통과
        JSONAssert.assertEquals(
            "{\"eventId\":\"" + capturedUuid + "\"}",
            "{\"eventId\":\"" + actualUuid + "\"}",
            new CustomComparator(JSONCompareMode.LENIENT,
                new Customization("eventId",
                    (o1, o2) -> o1 != null && o1.toString().matches(uuidRegex)))
        );
        // 예외 없이 통과 = 수정 올바름
    }
}
