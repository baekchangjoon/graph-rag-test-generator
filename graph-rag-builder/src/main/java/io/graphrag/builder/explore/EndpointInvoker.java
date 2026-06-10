package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;

/** endpoint 호출 + 해당 요청의 분기 커버리지 측정. 구현은 실행 환경(HTTP+JaCoCo)이 담당. */
@FunctionalInterface
public interface EndpointInvoker {

    InvocationOutcome invoke(JsonNode body);
}
