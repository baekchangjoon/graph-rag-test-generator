package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * REQ-012: 동일 입력 2회 발행 Kafka payload diff.
 *
 * <p>비-패턴 서버 생성 필드(UUID/ISO-8601 휴리스틱이 놓치는 시퀀스 ID 등)를
 * 두 payload 간 field-by-field diff로 검출한다. 검출된 값은
 * {@code CapturedEventEmit.nonDeterministicValues}에 기록되어 Generator의
 * {@code deterministicPayload}가 해당 필드를 제거/형식단언 대상으로 처리한다.
 * 입력 유래 값(substitutions keys)은 절대 비결정으로 표시하지 않는다(REQ-010 불변).
 */
final class KafkaPayloadDiffer {

    private KafkaPayloadDiffer() {
    }

    /**
     * 두 payload JSON 객체를 field-by-field로 비교해 서버 생성(비결정) 값 집합을 반환한다.
     *
     * <p>규칙:
     * <ul>
     *   <li>textual·number·boolean 최상위 필드의 값이 emission1과 emission2에서 다르면 비결정으로 판단.</li>
     *   <li>그 문자열 표현이 {@code inputDerivedValues}(입력 유래 치환 대상)에 포함되면 <b>제외</b>
     *       (REQ-010 불변 — 입력 유래 값은 절대 비결정으로 표시 금지).</li>
     *   <li>payload1과 payload2 양쪽 값(문자열 표현)을 반환 집합에 추가한다(Generator가 값-기반으로 조회).</li>
     *   <li>한쪽 payload에만 있는 필드는 무시한다.</li>
     *   <li>비-ObjectNode payload는 비교 불가 → 빈 집합 반환.</li>
     * </ul>
     *
     * @param payload1         1차 발행 payload (JsonNode)
     * @param payload2         2차 발행 payload (JsonNode)
     * @param inputDerivedValues 입력 유래 값 집합 (fixture.substitutions().keySet() 등)
     * @return 비결정으로 판단된 값 집합 (두 발행의 값 모두 포함, 문자열 표현)
     */
    static Set<String> diffNonDeterministicValues(JsonNode payload1, JsonNode payload2,
                                                   Set<String> inputDerivedValues) {
        if (!(payload1 instanceof ObjectNode obj1) || !(payload2 instanceof ObjectNode obj2)) {
            return Set.of();
        }
        Set<String> nonDeterministic = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = obj1.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode v1 = entry.getValue();
            JsonNode v2 = obj2.get(fieldName);
            if (v2 == null) {
                continue;
            }
            // I1 fix: textual, number(int/long/float/double), boolean을 모두 비교한다.
            // array/object/null은 비교 범위 외 → skip.
            if (!isScalar(v1) || !isScalar(v2)) {
                continue;
            }
            String text1 = v1.asText();
            String text2 = v2.asText();
            if (text1.equals(text2)) {
                continue;
            }
            // 값이 다름 → 서버 생성 후보. 입력 유래이면 제외(REQ-010).
            if (inputDerivedValues.contains(text1) || inputDerivedValues.contains(text2)) {
                continue;
            }
            nonDeterministic.add(text1);
            nonDeterministic.add(text2);
        }
        return Set.copyOf(nonDeterministic);
    }

    /** textual, number(int/long/float/double), boolean 노드이면 true. */
    private static boolean isScalar(JsonNode node) {
        return node.isTextual() || node.isNumber() || node.isBoolean();
    }
}
