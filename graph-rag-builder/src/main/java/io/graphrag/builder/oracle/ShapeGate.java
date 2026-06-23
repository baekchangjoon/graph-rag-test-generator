package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** LLM 후보값을 BodyShape에 그라운딩 — 존재하는 String 필드만 수용, 그 외 loud-fail 폐기. */
public final class ShapeGate {
    private static final Logger log = LoggerFactory.getLogger(ShapeGate.class);

    private ShapeGate() {
    }

    public static Map<String, Set<String>> filter(LlmFieldValues raw, BodyShape shape) {
        Map<String, String> typeByName = new TreeMap<>();
        for (BodyShape.BodyField f : shape.fields()) {
            typeByName.put(f.name(), f.javaType());
        }
        Map<String, Set<String>> out = new TreeMap<>();
        for (var e : raw.stringValuesByField().entrySet()) {
            String type = typeByName.get(e.getKey());
            if (type == null) {
                log.warn("llm oracle: dropping value for unknown field {}", e.getKey());
                continue;
            }
            if (!type.equals("java.lang.String")) {
                log.warn("llm oracle: dropping non-String field {} ({})", e.getKey(), type);
                continue;
            }
            out.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
        }
        return out;
    }
}
