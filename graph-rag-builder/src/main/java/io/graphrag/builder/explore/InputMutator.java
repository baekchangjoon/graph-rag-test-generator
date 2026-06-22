package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.JsonPaths;
import io.graphrag.builder.index.ValidationConstraintExtractor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                JsonPaths.removePath(body, name);
                return body;
            }));
            mutations.add(new Mutation("null-" + name, body -> {
                JsonPaths.putNullPath(body, name);
                return body;
            }));
            if (NUMERIC_TYPES.contains(field.javaType())) {
                mutations.add(new Mutation("zero-" + name, body -> {
                    JsonPaths.putPath(body, name, 0);
                    return body;
                }));
                mutations.add(new Mutation("negative-" + name, body -> {
                    JsonPaths.putPath(body, name, -1);
                    return body;
                }));
                // 범위 상한 분기용 (예: 재고/한도 초과)
                mutations.add(new Mutation("large-" + name, body -> {
                    JsonPaths.putPath(body, name, 1_000_000);
                    return body;
                }));
            } else if (field.javaType().equals("java.lang.String")) {
                mutations.add(new Mutation("empty-" + name, body -> {
                    JsonPaths.putPath(body, name, "");
                    return body;
                }));
                if (name.endsWith("Id") && name.length() > 2) {
                    mutations.add(new Mutation("missing-ref-" + name,
                            body -> {
                                JsonPaths.putPath(body, name, "missing-" + name);
                                return body;
                            }));
                }
                // handler의 enum-스타일 리터럴을 도메인 값 후보로 (docs/22 보완)
                for (String literal : literalCandidates) {
                    mutations.add(new Mutation("literal-" + name + "-" + literal,
                            body -> {
                                JsonPaths.putPath(body, name, literal);
                                return body;
                            }));
                }
            }
        }
        return mutations;
    }

    /** generic firstOrder + 제약 지향 변이를 합쳐 이름 기준 dedupe. 두 explorer 공용. */
    public static List<Mutation> forTarget(EndpointTarget target) {
        // 고신호(제약 지향 + enum + joint)를 generic firstOrder 앞에 둔다. 예산이 적을 때
        // firstOrder(remove/null/zero × 전 필드)가 bound/enum/joint를 굶기는 것을 방지
        // (다필드 가드는 bound로 valid-prefix seed를 만들고 enum/joint로 그 위를 열어야 도달).
        List<Mutation> all = new ArrayList<>(constraintDirected(target.mutableFields(),
                target.fieldConstraints(), target.conditionBounds(), target.stringCandidates()));
        all.addAll(enumValues(target.mutableFields(), target.enumConstants()));
        all.addAll(joint(target.mutableFields(), target.conjunctions()));
        all.addAll(joinGuards(target.mutableFields(), target.joinGuards()));
        all.addAll(interField(target.mutableFields(), target.interFieldTuples()));
        all.addAll(interFieldReal(target.mutableFields(), target.realInterFieldTuples()));
        all.addAll(realBounds(target.mutableFields(), target.realBounds()));
        all.addAll(firstOrder(target.mutableFields(), target.literalCandidates()));
        return dedupeByName(all);
    }

    /** inter-field 튜플(필드→값 동시충족 해)을 한 atomic 변이로. 튜플 전 필드가 body에 있을 때만. */
    public static List<Mutation> interField(List<BodyShape.BodyField> fields,
                                            List<Map<String, Long>> tuples) {
        HashSet<String> fieldNames = new HashSet<>();
        for (BodyShape.BodyField f : fields) {
            fieldNames.add(f.name());
        }
        List<Mutation> out = new ArrayList<>();
        for (Map<String, Long> tuple : tuples) {
            if (tuple.isEmpty() || !fieldNames.containsAll(tuple.keySet())) {
                continue;
            }
            // 이름에 값까지 포함(joint이 line을 넣듯) — 같은 필드쌍의 서로 다른 튜플이 dedupeByName에서
            // 충돌·소실하지 않게 한다.
            String name = "interfield-" + new java.util.TreeMap<>(tuple);
            Map<String, Long> t = tuple;
            out.add(new Mutation(name, body -> {
                t.forEach((field, value) -> JsonPaths.putPath(body, field, value.longValue()));
                return body;
            }));
        }
        return out;
    }

    /** float/double inter-field 튜플(필드→double 동시충족 해)을 한 atomic 변이로(작업 #4). 튜플 전 필드가 body에 있을 때만. */
    public static List<Mutation> interFieldReal(List<BodyShape.BodyField> fields,
                                                List<Map<String, Double>> realTuples) {
        HashSet<String> fieldNames = new HashSet<>();
        for (BodyShape.BodyField f : fields) {
            fieldNames.add(f.name());
        }
        List<Mutation> out = new ArrayList<>();
        for (Map<String, Double> tuple : realTuples) {
            if (tuple.isEmpty() || !fieldNames.containsAll(tuple.keySet())) {
                continue;
            }
            String name = "interfield-real-" + new java.util.TreeMap<>(tuple);
            Map<String, Double> t = tuple;
            out.add(new Mutation(name, body -> {
                t.forEach((field, value) -> JsonPaths.putPath(body, field, value));
                return body;
            }));
        }
        return out;
    }

    /** float/double 단일필드 경계 후보(Real solveBoundary 해)를 필드별 세팅 변이로(작업 #4). */
    public static List<Mutation> realBounds(List<BodyShape.BodyField> fields,
                                            Map<String, Set<Double>> reals) {
        HashSet<String> fieldNames = new HashSet<>();
        for (BodyShape.BodyField f : fields) {
            fieldNames.add(f.name());
        }
        List<Mutation> out = new ArrayList<>();
        for (var e : new java.util.TreeMap<>(reals).entrySet()) {
            String field = e.getKey();
            if (!fieldNames.contains(field)) {
                continue;
            }
            for (Double v : new java.util.TreeSet<>(e.getValue())) {
                out.add(new Mutation("realbound-" + field + "-" + v,
                        body -> {
                            JsonPaths.putPath(body, field, v);
                            return body;
                        }));
            }
        }
        return out;
    }

    /** enum 필드 → 선언된 각 상수 세팅 변이(VIP 등). enumConstants 키 미스 시 simple-name 폴백. */
    public static List<Mutation> enumValues(List<BodyShape.BodyField> fields,
                                            Map<String, List<String>> enumConstants) {
        List<Mutation> out = new ArrayList<>();
        for (BodyShape.BodyField field : fields) {
            List<String> consts = constantsFor(field.javaType(), enumConstants);
            if (consts == null) {
                continue;
            }
            String name = field.name();
            for (String c : consts) {
                out.add(new Mutation("enum-" + name + "-" + c, body -> {
                    JsonPaths.putPath(body, name, c);
                    return body;
                }));
            }
        }
        return out;
    }

    /** conjunction의 모든 원자 필드가 body에 있으면, 원자들을 동시에 만족값으로 세팅하는 단일 변이. */
    public static List<Mutation> joint(List<BodyShape.BodyField> fields,
                                       List<ConstraintExtractor.Conjunction> conjunctions) {
        HashSet<String> fieldNames = new HashSet<>();
        for (BodyShape.BodyField f : fields) {
            fieldNames.add(f.name());
        }
        List<Mutation> out = new ArrayList<>();
        for (ConstraintExtractor.Conjunction c : conjunctions) {
            if (c.atoms().isEmpty()
                    || !c.atoms().stream().allMatch(a -> fieldNames.contains(a.fieldRef()))) {
                continue;
            }
            String refs = c.atoms().stream().map(ConstraintExtractor.Atom::fieldRef)
                    .distinct().sorted().reduce((a, b) -> a + "_" + b).orElse("");
            String simpleClass = c.classFqn().substring(c.classFqn().lastIndexOf('.') + 1);
            String name = "joint-" + simpleClass + "-" + c.line() + "-" + refs;
            List<ConstraintExtractor.Atom> atoms = c.atoms();
            out.add(new Mutation(name, body -> {
                for (ConstraintExtractor.Atom a : atoms) {
                    switch (a.kind()) {
                        case NUMERIC -> JsonPaths.putPath(body, a.fieldRef(), satisfy(a.op(), a.numLiteral()));
                        case ENUM_EQ, STRING_EQ -> JsonPaths.putPath(body, a.fieldRef(), a.value());
                    }
                }
                return body;
            }));
        }
        return out;
    }

    /** 필드-대-필드 비교 가드(JoinGuard)를 lt/eq/gt(NUMERIC) 또는 eq/ne(STRING) 변이 쌍으로 전개. */
    public static List<Mutation> joinGuards(List<BodyShape.BodyField> fields,
                                            List<ConstraintExtractor.JoinGuard> guards) {
        HashSet<String> names = new HashSet<>();
        for (BodyShape.BodyField f : fields) { names.add(f.name()); }
        List<Mutation> out = new ArrayList<>();
        for (ConstraintExtractor.JoinGuard g : guards) {
            if (!names.contains(g.leftRef()) || !names.contains(g.rightRef())) { continue; }
            String base = "joinguard-" + g.leftRef() + "-" + g.op() + "-" + g.rightRef() + "-";
            if (g.kind() == ConstraintExtractor.JoinKind.NUMERIC) {
                out.add(numMutation(base + "lt", g.leftRef(), 0L, g.rightRef(), 1L));
                out.add(numMutation(base + "eq", g.leftRef(), 0L, g.rightRef(), 0L));
                out.add(numMutation(base + "gt", g.leftRef(), 1L, g.rightRef(), 0L));
            } else {
                out.add(strMutation(base + "eq", g.leftRef(), "x", g.rightRef(), "x"));
                out.add(strMutation(base + "ne", g.leftRef(), "x", g.rightRef(), "y"));
            }
        }
        return out;
    }

    private static Mutation numMutation(String name, String leftField, long leftVal,
                                        String rightField, long rightVal) {
        return new Mutation(name, body -> {
            JsonPaths.putPath(body, leftField, leftVal);
            JsonPaths.putPath(body, rightField, rightVal);
            return body;
        });
    }

    private static Mutation strMutation(String name, String leftField, String leftVal,
                                        String rightField, String rightVal) {
        return new Mutation(name, body -> {
            JsonPaths.putPath(body, leftField, leftVal);
            JsonPaths.putPath(body, rightField, rightVal);
            return body;
        });
    }

    private static long satisfy(String op, long literal) {
        return switch (op) {
            case "<" -> literal - 1;
            case ">", "!=" -> literal + 1;
            default -> literal;   // <=, >=, ==
        };
    }

    private static List<String> constantsFor(String javaType, Map<String, List<String>> enumConstants) {
        List<String> consts = enumConstants.get(javaType);
        if (consts != null) {
            return consts;
        }
        String simple = javaType.substring(javaType.lastIndexOf('.') + 1);
        return enumConstants.entrySet().stream()
                .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /**
     * Bean Validation 제약 + handler 비교식 경계를 위반/경계 변이로 환류 (결정적).
     * 필드 선언 순서 → 제약 종류 고정 순서. 값 적용은 generic firstOrder와 별개이며,
     * 같은 (field,value)로 수렴하면 explorer의 markTried가 예산 낭비를 차단한다.
     */
    public static List<Mutation> constraintDirected(
            List<BodyShape.BodyField> fields,
            Map<String, List<ValidationConstraintExtractor.FieldConstraint>> fieldConstraints,
            Map<String, Set<Long>> conditionBounds,
            Map<String, Set<String>> stringCandidates) {
        List<Mutation> mutations = new ArrayList<>();
        for (BodyShape.BodyField field : fields) {
            String name = field.name();
            boolean numeric = NUMERIC_TYPES.contains(field.javaType());
            boolean string = field.javaType().equals("java.lang.String");
            for (ValidationConstraintExtractor.FieldConstraint c :
                    fieldConstraints.getOrDefault(name, List.of())) {
                switch (c.kind()) {
                    case NOT_NULL, NOT_BLANK, PATTERN -> {
                        // null/빈문자는 generic firstOrder가 덮고, @Pattern 값 생성은 보류(YAGNI).
                    }
                    case SIZE_MIN -> {
                        if (string && c.numArg() > 0) {
                            int m = (int) c.numArg();
                            putStr(mutations, "size-min-violate-" + name, name, "x".repeat(m - 1));
                            putStr(mutations, "size-min-edge-" + name, name, "x".repeat(m));
                        }
                    }
                    case SIZE_MAX -> {
                        if (string) {
                            int m = (int) c.numArg();
                            putStr(mutations, "size-max-violate-" + name, name, "x".repeat(m + 1));
                            putStr(mutations, "size-max-edge-" + name, name, "x".repeat(m));
                        }
                    }
                    case MIN -> {
                        if (numeric) {
                            putLong(mutations, "min-violate-" + name, name, c.numArg() - 1);
                            putLong(mutations, "min-edge-" + name, name, c.numArg());
                        }
                    }
                    case MAX -> {
                        if (numeric) {
                            putLong(mutations, "max-violate-" + name, name, c.numArg() + 1);
                            putLong(mutations, "max-edge-" + name, name, c.numArg());
                        }
                    }
                    case POSITIVE -> {
                        if (numeric) {
                            putLong(mutations, "pos-violate-zero-" + name, name, 0);
                            putLong(mutations, "pos-violate-neg-" + name, name, -1);
                        }
                    }
                    case POSITIVE_OR_ZERO -> {
                        if (numeric) {
                            putLong(mutations, "posz-violate-" + name, name, -1);
                        }
                    }
                    case NEGATIVE -> {
                        if (numeric) {
                            putLong(mutations, "neg-violate-zero-" + name, name, 0);
                            putLong(mutations, "neg-violate-pos-" + name, name, 1);
                        }
                    }
                    case NEGATIVE_OR_ZERO -> {
                        if (numeric) {
                            putLong(mutations, "negz-violate-" + name, name, 1);
                        }
                    }
                    case EMAIL -> {
                        if (string) {
                            putStr(mutations, "email-violate-" + name, name, "not-an-email");
                        }
                    }
                }
            }
            if (numeric) {
                for (Long v : conditionBounds.getOrDefault(name, Set.of())) {
                    putLong(mutations, "bound-" + name + "-" + v, name, v);
                }
            }
            if (string) {
                for (String v : stringCandidates.getOrDefault(name, Set.of())) {
                    putStr(mutations, "streq-" + name + "-" + v, name, v);
                }
            }
        }
        return dedupeByName(mutations);
    }

    private static void putStr(List<Mutation> out, String mName, String field, String value) {
        out.add(new Mutation(mName, body -> {
            JsonPaths.putPath(body, field, value);
            return body;
        }));
    }

    private static void putLong(List<Mutation> out, String mName, String field, long value) {
        out.add(new Mutation(mName, body -> {
            JsonPaths.putPath(body, field, value);
            return body;
        }));
    }

    private static List<Mutation> dedupeByName(List<Mutation> mutations) {
        LinkedHashMap<String, Mutation> byName = new LinkedHashMap<>();
        for (Mutation m : mutations) {
            byName.putIfAbsent(m.name(), m);
        }
        return new ArrayList<>(byName.values());
    }

    public static JsonNode copy(JsonNode body) {
        return body.deepCopy();
    }

    /**
     * body 타입에 따라 변이를 안전하게 적용한다.
     * - ObjectNode: 직접 변이 적용.
     * - ArrayNode(비어있지 않고 첫 요소가 ObjectNode): element[0]에만 변이 적용.
     * - 그 외: body 그대로 반환.
     */
    public static JsonNode applyToBody(JsonNode body, Mutation m) {
        if (body instanceof ObjectNode obj) {
            return m.apply().apply(obj);
        }
        if (body instanceof ArrayNode arr && !arr.isEmpty() && arr.get(0) instanceof ObjectNode el) {
            m.apply().apply(el);   // element[0] 대표 변이 (arr는 호출부에서 깊은 복사된 본문)
        }
        return body;
    }
}
