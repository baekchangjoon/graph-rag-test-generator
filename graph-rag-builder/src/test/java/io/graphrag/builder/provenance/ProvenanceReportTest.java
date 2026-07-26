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
                .isEqualTo(original)
                .as("JSON round-trip should preserve ProvenanceReport equality");

        // Assert: 각 nested type도 동등성 확인
        assertThat(deserialized.guards()).isEqualTo(original.guards());
        assertThat(deserialized.unguarded()).isEqualTo(original.unguarded());
        assertThat(deserialized.unresolved()).isEqualTo(original.unresolved());
    }
}
