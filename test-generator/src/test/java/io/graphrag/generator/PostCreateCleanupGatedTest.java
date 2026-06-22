package io.graphrag.generator;

import io.graphrag.generator.compose.ComposedFixture;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Outcome;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-005: postCreateCleanup 게이트는 raw HTTP 상태 범위가 아닌 outcome 기준으로 판단해야 한다.
 * 200으로 감싸진 에러 엔벨로프(outcome=FAILURE, expectedStatus=200)에서는 cleanup을 주입하지 않는다.
 */
class PostCreateCleanupGatedTest {

    private static TableSchema bookings() {
        return new TableSchema("bookings", List.of(
                new ColumnSchema("id", "BIGINT", false, true, true),
                new ColumnSchema("customer_email", "VARCHAR", true, false, false)),
                List.of(), List.of());
    }

    private static CapturedSql insertBookings() {
        return new CapturedSql("sql-1", "p1", "INSERT",
                "INSERT INTO bookings (customer_email) VALUES (?)", "bookings", List.of());
    }

    private static List<ComposedFixture.Assertion> respHasId() {
        return List.of(new ComposedFixture.Assertion("id", "notNullValue()"),
                new ComposedFixture.Assertion("status", "equalTo(\"PENDING\")"));
    }

    /**
     * 핵심: 200이지만 outcome=FAILURE인 에러 엔벨로프 응답 → cleanup 주입 금지.
     * 현재 구현은 expectedStatus 200이 [200,300) 범위라 게이트를 통과해 cleanup을 반환한다(BUG).
     */
    @Test
    void post200_outcomeFailure_envelopedError_noCleanup() {
        Map<String, Object> c = Generator.postCreateCleanup("POST", 200,
                Outcome.Kind.FAILURE,
                List.of(insertBookings()), List.of(bookings()), respHasId(), List.of());
        assertThat(c).isNull();
    }

    /**
     * 회귀: 201 + outcome=SUCCESS → cleanup 주입.
     */
    @Test
    void post201_outcomeSuccess_emitsCleanup() {
        Map<String, Object> c = Generator.postCreateCleanup("POST", 201,
                Outcome.Kind.SUCCESS,
                List.of(insertBookings()), List.of(bookings()), respHasId(), List.of());
        assertThat(c).isNotNull();
        assertThat(c.get("table")).isEqualTo("bookings");
    }

    /**
     * 회귀: 200 + outcome=SUCCESS (정상 2xx 성공) → cleanup 주입.
     */
    @Test
    void post200_outcomeSuccess_emitsCleanup() {
        Map<String, Object> c = Generator.postCreateCleanup("POST", 200,
                Outcome.Kind.SUCCESS,
                List.of(insertBookings()), List.of(bookings()), respHasId(), List.of());
        assertThat(c).isNotNull();
        assertThat(c.get("table")).isEqualTo("bookings");
    }
}
