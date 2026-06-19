package io.graphrag.generator;

import io.graphrag.generator.compose.ComposedFixture;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fix#3 (PR #62 flaky 위생): 성공 POST(2xx)가 IDENTITY(autoIncrement) PK 행을 만들고 그 행에 대한
 * param-bound cleanup 이 없으면, 응답 id 를 캡처해 deferDelete 로 정리한다. 잔류 행이 다음 absent-id
 * read 의 부재 가정을 깨는 것을 줄인다(Fix#1 의 보조). autoIncrement PK 가 아니면 트리거하지 않아
 * 기존 golden 은 불변.
 */
class GeneratorPostCreateCleanupTest {

    private static TableSchema bookings(boolean autoIncPk) {
        return new TableSchema("bookings", List.of(
                new ColumnSchema("id", "BIGINT", false, true, autoIncPk),
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

    @Test
    void post201_autoIncPk_noExistingDelete_emitsCleanup() {
        Map<String, Object> c = Generator.postCreateCleanup("POST", 201,
                List.of(insertBookings()), List.of(bookings(true)), respHasId(), List.of());
        assertThat(c).isNotNull();
        assertThat(c.get("table")).isEqualTo("bookings");
        assertThat(c.get("pkColumn")).isEqualTo("id");
        assertThat(c.get("pkField")).isEqualTo("id");
    }

    @Test
    void post201_nonAutoIncPk_noCleanup() {
        assertThat(Generator.postCreateCleanup("POST", 201,
                List.of(insertBookings()), List.of(bookings(false)), respHasId(), List.of())).isNull();
    }

    @Test
    void post201_tableAlreadyCleaned_noCleanup() {
        var existing = List.of(new ComposedFixture.Stmt("DELETE FROM bookings WHERE customer_email = ?", List.of("e")));
        assertThat(Generator.postCreateCleanup("POST", 201,
                List.of(insertBookings()), List.of(bookings(true)), respHasId(), existing)).isNull();
    }

    @Test
    void get200_notCreate_noCleanup() {
        assertThat(Generator.postCreateCleanup("GET", 200,
                List.of(insertBookings()), List.of(bookings(true)), respHasId(), List.of())).isNull();
    }

    @Test
    void post404_notSuccess_noCleanup() {
        assertThat(Generator.postCreateCleanup("POST", 404,
                List.of(insertBookings()), List.of(bookings(true)), respHasId(), List.of())).isNull();
    }

    @Test
    void post201_responseLacksPk_noCleanup() {
        var noId = List.of(new ComposedFixture.Assertion("status", "equalTo(\"PENDING\")"));
        assertThat(Generator.postCreateCleanup("POST", 201,
                List.of(insertBookings()), List.of(bookings(true)), noId, List.of())).isNull();
    }

    @Test
    void post201_snakeCasePk_camelCasesResponseField() {
        TableSchema t = new TableSchema("bookings", List.of(
                new ColumnSchema("booking_id", "BIGINT", false, true, true)), List.of(), List.of());
        var resp = List.of(new ComposedFixture.Assertion("bookingId", "notNullValue()"));
        Map<String, Object> c = Generator.postCreateCleanup("POST", 201,
                List.of(insertBookings()), List.of(t), resp, List.of());
        assertThat(c).isNotNull();
        assertThat(c.get("pkColumn")).isEqualTo("booking_id");
        assertThat(c.get("pkField")).isEqualTo("bookingId");
    }
}
