package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 엔드포인트 1건의 LLM 값 생성 요청. 프롬프트 주입·비용 완화를 위해 handlerSource는 메서드
 * 본문만 담고, 필드·제약은 structured 파라미터로 분리한다.
 */
public record LlmRequest(String endpointId, String handlerSource,
                         List<BodyShape.BodyField> fields,
                         Map<String, String> patternByField,
                         Set<String> emailFields, String modelId) {
}
