package io.graphrag.generator.compose;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.Json;

import java.util.List;
import java.util.Map;

/**
 * 캡처된 외부 HTTP 호출 → testlib 스텁 등록 코드 합성 (roadmap 2.4).
 * 격리 규칙 (docs/04): propagation 있으면 baggage 매칭, 없으면 직렬 실행 마크.
 */
public class HttpMockComposer {

    public record ComposedMocks(String block, boolean propagationMissing) {
    }

    public ComposedMocks compose(List<CapturedHttpCall> calls) {
        if (calls.isEmpty()) {
            return new ComposedMocks("", false);
        }
        StringBuilder block = new StringBuilder();
        boolean propagationMissing = false;
        for (CapturedHttpCall call : calls) {
            propagationMissing |= !call.baggagePropagated();
            block.append("\n        scope.http().stub(")
                    .append(javaString(call.method())).append(", ")
                    .append(javaString(call.urlPath())).append(")");
            for (Map.Entry<String, String> param : call.query().entrySet()) {
                block.append("\n                .withQueryParam(")
                        .append(javaString(param.getKey())).append(", ")
                        .append(javaString(param.getValue())).append(")");
            }
            if (call.baggagePropagated()) {
                block.append("\n                .withBaggageTestId(scope.testId())");
            }
            block.append("\n                .respondJson(").append(call.responseStatus())
                    .append(", ").append(javaString(stubBody(call))).append(")")
                    .append("\n                .register();\n");
        }
        return new ComposedMocks(block.toString(), propagationMissing);
    }

    /** 스텁 응답 = consumedFields 투영 (2.5). 투영 불가 시 캡처 응답 전체. */
    private static String stubBody(CapturedHttpCall call) {
        try {
            var node = Json.mapper().readTree(call.responseBody());
            if (!call.consumedFields().isEmpty() && node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.retain(call.consumedFields());
            }
            return Json.mapper().writeValueAsString(node);
        } catch (Exception e) {
            return call.responseBody();
        }
    }

    private static String javaString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
