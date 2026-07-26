package io.graphrag.builder.provenance;

import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProvenanceReport의 JSON 직렬화·역직렬화 round-trip 테스트.
 */
class ProvenanceReportTest {

    @Test
    void roundTrip() throws Exception {
        // Arrange: 각 nested type의 인스턴스 생성
        var origin = ProvenanceReport.Origin.INPUT;
        var valueRef = new ProvenanceReport.ValueRef(
                origin,
                "$.user.id",
                "users_table",
                "id",
                "POST /transfer",
                "transferField",
                "Long",
                "userId",
                "12345"
        );
        var guardFact = new ProvenanceReport.GuardFact(
                "TransferService.java:42",
                "checkLimit",
                List.of(valueRef)
        );
        var unguardedField = new ProvenanceReport.UnguardedField(
                "$.amount",
                "BigDecimal",
                "transferAmount"
        );
        var unresolved = new ProvenanceReport.Unresolved(
                "DynamicService.java:15",
                ProvenanceReport.Reason.REFLECTION,
                "ReflectedType"
        );

        var original = new ProvenanceReport(
                "endpoint-123",
                List.of(guardFact),
                List.of(unguardedField),
                List.of(unresolved)
        );

        // Act: JSON 직렬화 → 역직렬화
        var mapper = Json.mapper();
        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, ProvenanceReport.class);

        // Assert: round-trip 후 동등성 확인
        assertThat(deserialized)
                .as("JSON round-trip should preserve ProvenanceReport equality")
                .isEqualTo(original);

        // Assert: 각 nested type도 동등성 확인
        assertThat(deserialized.guards()).isEqualTo(original.guards());
        assertThat(deserialized.unguarded()).isEqualTo(original.unguarded());
        assertThat(deserialized.unresolved()).isEqualTo(original.unresolved());
    }

    @Test
    void jsonIncludeNonNullExcludesNullFields() throws Exception {
        // Arrange: null 필드를 포함한 ValueRef 생성
        // origin=INPUT, table/column/stubField/literal은 null
        var valueRefWithNulls = new ProvenanceReport.ValueRef(
                ProvenanceReport.Origin.INPUT,
                "$.userId",
                null,  // table is null
                null,  // column is null
                "POST /transfer",
                null,  // stubField is null
                "Long",
                "userId",
                null   // literal is null
        );

        var guardFactWithNulls = new ProvenanceReport.GuardFact(
                "Service.java:10",
                "checkLimit",
                List.of(valueRefWithNulls)
        );

        var reportWithNulls = new ProvenanceReport(
                "endpoint-456",
                List.of(guardFactWithNulls),
                List.of(),
                List.of()
        );

        // Act: JSON 직렬화
        var mapper = Json.mapper();
        var json = mapper.writeValueAsString(reportWithNulls);

        // Assert: null 필드의 키가 JSON 문자열에 없어야 함
        assertThat(json)
                .as("null fields should not appear in JSON serialization")
                .doesNotContain("\"table\"")
                .doesNotContain("\"column\"")
                .doesNotContain("\"stubField\"")
                .doesNotContain("\"literal\"");

        // Assert: non-null 필드는 JSON에 포함되어야 함
        assertThat(json)
                .as("non-null fields should appear in JSON serialization")
                .contains("\"origin\":\"INPUT\"")
                .contains("\"jsonPath\":\"$.userId\"")
                .contains("\"callSite\":\"POST /transfer\"")
                .contains("\"javaType\":\"Long\"")
                .contains("\"semanticHint\":\"userId\"");

        // Assert: round-trip 후에도 null 필드는 null이어야 함
        var deserialized = mapper.readValue(json, ProvenanceReport.class);
        var deserializedValueRef = deserialized.guards().get(0).operands().get(0);
        assertThat(deserializedValueRef.table()).isNull();
        assertThat(deserializedValueRef.column()).isNull();
        assertThat(deserializedValueRef.stubField()).isNull();
        assertThat(deserializedValueRef.literal()).isNull();
    }
}
