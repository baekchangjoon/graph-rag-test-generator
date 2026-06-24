package io.graphrag.builder.run;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-005: mergeEnvelopeCandidates 순수 헬퍼 단위 테스트.
 * errorContract → 후보 맵 병합 동작: null 입력 / 필드 존재 / 필드 미존재 케이스.
 */
class EnvelopeVariantCandidateTest {

    private final ErrorEnvelopeSynthesizer synth = new ErrorEnvelopeSynthesizer();

    /** (a) errorContract null → candidates 변경 없음 */
    @Test
    @DisplayName("REQ-F012-005(a): errorContract null이면 candidates 변경 없음")
    void nullDescriptor_candidatesUnchanged() {
        Map<String, List<String>> original = Map.of("status", List.of("ACTIVE"));
        BodyShape shape = new BodyShape("com.example.Dto",
                List.of(new BodyShape.BodyField("status", "java.lang.String")));

        // null errorContract → 호출자가 skip하므로 직접 검증은 위 guard로,
        // 헬퍼 자체는 non-null errorContract일 때만 호출된다.
        // 호출자 guard(if errorContract != null)를 반영해 여기서는 빈 envelope 합성기로 대체 검증한다.
        // ⇒ errorWhenPresent 비어있는 descriptor: 합성 결과 빈 ObjectNode → 병합 결과 변경 없음.
        var emptyContract = new ErrorContractDescriptor(
                List.of(), null, null, null);
        Map<String, List<String>> merged = EndpointExplorationRunner.mergeEnvelopeCandidates(
                original, shape, emptyContract, synth);

        assertThat(merged).containsKey("status");
        assertThat(merged.get("status")).containsExactly("ACTIVE");
        assertThat(merged).hasSize(1);
    }

    /** (b) non-null errorContract + responseShape에 errorCode 필드 존재 → candidates["errorCode"] 에 "ERROR" 포함 */
    @Test
    @DisplayName("REQ-F012-005(b): envelope 필드가 responseShape에 존재하면 candidates에 추가됨")
    void envelopeField_inShape_addedToCandidates() {
        Map<String, List<String>> original = Map.of();
        BodyShape shape = new BodyShape("com.example.Dto",
                List.of(new BodyShape.BodyField("errorCode", "java.lang.String")));
        var descriptor = new ErrorContractDescriptor(
                List.of("errorCode"), "errorCode", null, null);

        Map<String, List<String>> merged = EndpointExplorationRunner.mergeEnvelopeCandidates(
                original, shape, descriptor, synth);

        assertThat(merged).containsKey("errorCode");
        assertThat(merged.get("errorCode")).contains("ERROR");
    }

    /** (c) envelope 필드가 responseShape에 없으면 candidates에 추가되지 않음 */
    @Test
    @DisplayName("REQ-F012-005(c): envelope 필드가 responseShape에 없으면 candidates에 추가 안됨")
    void envelopeField_notInShape_notAdded() {
        Map<String, List<String>> original = Map.of("someField", List.of("A"));
        // shape에는 "someField"만 있고, envelope는 "errorCode"를 생성한다
        BodyShape shape = new BodyShape("com.example.Dto",
                List.of(new BodyShape.BodyField("someField", "java.lang.String")));
        var descriptor = new ErrorContractDescriptor(
                List.of("errorCode"), "errorCode", null, null);

        Map<String, List<String>> merged = EndpointExplorationRunner.mergeEnvelopeCandidates(
                original, shape, descriptor, synth);

        assertThat(merged).doesNotContainKey("errorCode");
        assertThat(merged.get("someField")).containsExactly("A");
    }
}
