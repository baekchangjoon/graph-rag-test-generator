package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/** 결정적 boundary-value 변이 카탈로그. Random 금지 — 필드/변이자 순서 고정. */
public final class InputMutator {

    private static final Set<String> NUMERIC_TYPES = Set.of(
            "java.lang.Integer", "int", "java.lang.Long", "long",
            "java.lang.Short", "short", "java.lang.Double", "double",
            "java.lang.Float", "float", "java.math.BigDecimal");

    public record Mutation(String name, UnaryOperator<ObjectNode> apply) {
    }

    private InputMutator() {
    }

    public static List<Mutation> firstOrder(List<BodyShape.BodyField> fields) {
        return firstOrder(fields, List.of());
    }

    public static List<Mutation> firstOrder(List<BodyShape.BodyField> fields, List<String> literalCandidates) {
        List<Mutation> mutations = new ArrayList<>();
        for (BodyShape.BodyField field : fields) {
            String name = field.name();
            mutations.add(new Mutation("remove-" + name, body -> {
                body.remove(name);
                return body;
            }));
            mutations.add(new Mutation("null-" + name, body -> {
                body.putNull(name);
                return body;
            }));
            if (NUMERIC_TYPES.contains(field.javaType())) {
                mutations.add(new Mutation("zero-" + name, body -> body.put(name, 0)));
                mutations.add(new Mutation("negative-" + name, body -> body.put(name, -1)));
                // 범위 상한 분기용 (예: 재고/한도 초과)
                mutations.add(new Mutation("large-" + name, body -> body.put(name, 1_000_000)));
            } else if (field.javaType().equals("java.lang.String")) {
                mutations.add(new Mutation("empty-" + name, body -> body.put(name, "")));
                if (name.endsWith("Id") && name.length() > 2) {
                    mutations.add(new Mutation("missing-ref-" + name,
                            body -> body.put(name, "missing-" + name)));
                }
                // handler의 enum-스타일 리터럴을 도메인 값 후보로 (docs/22 보완)
                for (String literal : literalCandidates) {
                    mutations.add(new Mutation("literal-" + name + "-" + literal,
                            body -> body.put(name, literal)));
                }
            }
        }
        return mutations;
    }

    public static ObjectNode copy(JsonNode body) {
        return body.deepCopy();
    }
}
