package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.Json;

/**
 * 에러 envelope 응답 바디를 결정론적으로 합성한다.
 * errorWhenPresent 트리거 필드를 비어있지 않은 값("ERROR")으로 채우고,
 * semanticStatusField도 같은 센티넬로 채운다.
 * errorDetailField가 지정된 경우 errorDetailContains 값(없으면 빈 문자열)으로 채운다.
 */
public final class ErrorEnvelopeSynthesizer {

    public JsonNode synthesize(ErrorContractDescriptor d) {
        ObjectNode o = Json.mapper().createObjectNode();
        for (String trigger : d.errorWhenPresent()) {
            if (trigger != null && !trigger.isBlank()) o.put(trigger, "ERROR");
        }
        if (d.semanticStatusField() != null && !d.semanticStatusField().isBlank()) {
            o.put(d.semanticStatusField(), "ERROR");             // 결정적 센티넬
        }
        if (d.errorDetailField() != null && !d.errorDetailField().isBlank()) {
            o.put(d.errorDetailField(), d.errorDetailContains() == null ? "" : d.errorDetailContains());
        }
        return o;
    }
}
