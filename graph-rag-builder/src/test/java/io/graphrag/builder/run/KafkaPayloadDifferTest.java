package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-012 단위 테스트: KafkaPayloadDiffer.diffNonDeterministicValues().
 *
 * <p>비-패턴 서버 생성 값(UUID/ISO-8601 아닌 시퀀스 ID 등)을 두 payload 간 diff로 검출하며,
 * 입력 유래 값은 결코 비결정으로 표시하지 않음을 검증한다(REQ-010 불변).
 */
@DisplayName("REQ-012: KafkaPayloadDiffer — 2회 발행 field-by-field diff")
class KafkaPayloadDifferTest {

    @Test
    @DisplayName("REQ-012: 값이 다른 필드의 두 값을 모두 비결정 집합에 포함, 동일 필드는 제외")
    void diffingTwoEmissions_detectsChangedField_andExcludesUnchangedField() {
        ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
        payload1.put("eventId", "a-1");
        payload1.put("userId", "probe");

        ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
        payload2.put("eventId", "a-2");
        payload2.put("userId", "probe");

        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                payload1, payload2, Set.of("probe"));

        assertThat(result).containsExactlyInAnyOrder("a-1", "a-2");
    }

    @Test
    @DisplayName("REQ-012: 동일한 두 payload → 비결정 없음 (empty)")
    void identicalPayloads_returnEmptySet() {
        ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
        payload1.put("eventId", "fixed-id");
        payload1.put("amount", 100);

        ObjectNode payload2 = payload1.deepCopy();

        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                payload1, payload2, Set.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("REQ-012: 입력 유래 값은 변해도 비결정으로 표시하지 않음 (REQ-010 불변)")
    void inputDerivedValues_neverMarkedNonDeterministic_evenIfChanged() {
        ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
        payload1.put("eventId", "seq-1");
        payload1.put("userId", "probe-user");   // 입력 유래

        ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
        payload2.put("eventId", "seq-2");
        payload2.put("userId", "probe-user-2"); // 다르지만 입력 유래

        // userId의 두 값이 모두 inputDerivedValues에 있으면 제외
        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                payload1, payload2, Set.of("probe-user", "probe-user-2"));

        // eventId만 비결정, userId는 제외
        assertThat(result).containsExactlyInAnyOrder("seq-1", "seq-2");
        assertThat(result).doesNotContain("probe-user", "probe-user-2");
    }

    @Test
    @DisplayName("REQ-012: 비-ObjectNode payload → 빈 집합 (안전 폴백)")
    void nonObjectPayload_returnsEmpty() {
        var arrayNode = JsonNodeFactory.instance.arrayNode();
        arrayNode.add("a");

        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                arrayNode, arrayNode, Set.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("REQ-012: 한쪽 payload에만 있는 필드는 무시")
    void fieldOnlyInOnePayload_isIgnored() {
        ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
        payload1.put("eventId", "e-1");
        payload1.put("extra", "only-in-first");

        ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
        payload2.put("eventId", "e-2");

        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                payload1, payload2, Set.of());

        // eventId만 감지, extra는 payload2에 없으니 무시
        assertThat(result).containsExactlyInAnyOrder("e-1", "e-2");
    }

    @Test
    @DisplayName("REQ-012 I1: number 필드(시퀀스 ID)가 두 발행 간 다르면 비결정으로 감지됨")
    void numberField_seqId_diffsBetweenEmissions_detected() {
        ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
        payload1.put("seqId", 1L);
        payload1.put("userId", "probe");

        ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
        payload2.put("seqId", 2L);
        payload2.put("userId", "probe");

        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                payload1, payload2, Set.of("probe"));

        // seqId 두 값 모두 비결정으로 감지, userId(입력 유래)는 제외
        assertThat(result).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    @DisplayName("REQ-012 I1: boolean 필드가 두 발행 간 다르면 비결정으로 감지됨")
    void booleanField_diffsBetweenEmissions_detected() {
        ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
        payload1.put("active", true);

        ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
        payload2.put("active", false);

        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                payload1, payload2, Set.of());

        assertThat(result).containsExactlyInAnyOrder("true", "false");
    }

    @Test
    @DisplayName("REQ-012 I1: number 필드이지만 입력 유래 값이면 비결정으로 표시 안 함 (REQ-010 불변)")
    void numberField_inputDerived_notMarkedNonDeterministic() {
        ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
        payload1.put("amount", 100);

        ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
        payload2.put("amount", 200);

        // 두 값이 모두 입력 유래면 제외
        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                payload1, payload2, Set.of("100", "200"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("REQ-012 I1: 두 발행의 number 값이 동일하면 비결정 아님")
    void identicalNumberFields_notNonDeterministic() {
        ObjectNode payload1 = JsonNodeFactory.instance.objectNode();
        payload1.put("seqId", 42L);

        ObjectNode payload2 = JsonNodeFactory.instance.objectNode();
        payload2.put("seqId", 42L);

        Set<String> result = KafkaPayloadDiffer.diffNonDeterministicValues(
                payload1, payload2, Set.of());

        assertThat(result).isEmpty();
    }
}
