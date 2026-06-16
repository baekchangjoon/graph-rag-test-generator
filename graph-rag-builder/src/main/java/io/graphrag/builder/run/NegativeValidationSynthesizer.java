package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import io.graphrag.model.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * happy body를 복제해 Bean Validation 제약을 한 필드만 위반시킨 변종을 합성한다(B1, 커버리지 전용).
 * 변종 1개 = 제약 1개 위반(단일필드 격리) → reject arm(4xx) 분기 귀속이 명확. 시간/Random 미사용(결정성).
 * 어노테이션 제약만 다룬다(D2) — 명령형 if-throw 가드는 비목표(별도 후속).
 */
public class NegativeValidationSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(NegativeValidationSynthesizer.class);

    /** PATTERN 위반 후보(우연 매치 시 다음 후보로). 결정적 순서. */
    private static final List<String> PATTERN_MISMATCH_CANDIDATES =
            List.of("negval!@#", "!!!", "negval", " ");

    public record NegativeVariant(String field, Kind kind, ObjectNode body) {
    }

    /**
     * shape의 필드에 걸린 제약마다 위반 변종 1개를 합성한다. (field, kind)를 field명·kind 정렬 후
     * 앞에서 최대 {@code cap}개, 초과분은 드롭+log. 위반값 합성 불가(PATTERN no-mismatch)는 skip.
     */
    public static List<NegativeVariant> synthesizeNegativeValidationVariants(
            BodyShape shape,
            Map<String, List<FieldConstraint>> constraints,
            ObjectNode happyBody,
            int cap) {
        if (shape == null || constraints.isEmpty() || happyBody == null) {
            return List.of();
        }
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (BodyShape.BodyField f : shape.fields()) {
            fieldTypes.put(f.name(), f.javaType());
        }
        List<FieldConstraint> candidates = new ArrayList<>();
        for (Map.Entry<String, List<FieldConstraint>> e : constraints.entrySet()) {
            if (fieldTypes.containsKey(e.getKey())) {   // shape에 없는 필드는 위반 의미 없음
                candidates.addAll(e.getValue());
            }
        }
        candidates.sort(Comparator.comparing(FieldConstraint::field)
                .thenComparingInt(c -> c.kind().ordinal()));

        List<NegativeVariant> variants = new ArrayList<>();
        int dropped = 0;
        for (FieldConstraint c : candidates) {
            if (variants.size() >= cap) {
                dropped++;
                continue;
            }
            ObjectNode body = happyBody.deepCopy();
            if (applyViolation(body, c, fieldTypes.get(c.field()))) {
                variants.add(new NegativeVariant(c.field(), c.kind(), body));
            }
        }
        if (dropped > 0) {
            log.info("negative-validation: capped at {} variant(s), dropped {} candidate(s)", cap, dropped);
        }
        return variants;
    }

    /** body의 field를 c.kind 위반값으로 덮는다. 위반값 합성 불가면 false(변종 미생성). */
    private static boolean applyViolation(ObjectNode body, FieldConstraint c, String javaType) {
        String field = c.field();
        boolean collection = isCollection(javaType);
        switch (c.kind()) {
            case NOT_NULL -> body.remove(field);
            case NOT_BLANK -> {
                // 추출기가 @NotBlank와 @NotEmpty를 NOT_BLANK로 합치므로 둘 다 위반하는 값이어야 한다.
                // String이면 빈 문자열 ""(@NotBlank의 non-blank + @NotEmpty의 non-empty 동시 위반 — 공백 "   "는
                // @NotEmpty를 통과시킴). Collection이면 빈 배열([]) — Collection에 ""는 Jackson 역직렬화 에러.
                if (collection) { body.set(field, Json.mapper().createArrayNode()); }
                else { body.put(field, ""); }
            }
            case SIZE_MIN -> putSized(body, field, (int) c.numArg() - 1, collection);
            case SIZE_MAX -> putSized(body, field, (int) c.numArg() + 1, collection);
            case MIN -> body.put(field, c.numArg() - 1);
            case MAX -> body.put(field, c.numArg() + 1);
            case POSITIVE -> body.put(field, 0);
            case POSITIVE_OR_ZERO -> body.put(field, -1);
            case NEGATIVE -> body.put(field, 0);
            case NEGATIVE_OR_ZERO -> body.put(field, 1);
            case EMAIL -> body.put(field, "not-an-email");
            case PATTERN -> {
                String sentinel = patternMismatch(c.strArg());
                if (sentinel == null) {
                    log.info("negative-validation: no PATTERN mismatch for {} (regex={}), skip",
                            field, c.strArg());
                    return false;
                }
                body.put(field, sentinel);
            }
        }
        return true;
    }

    /** 길이 len(음수면 0)인 위반값: String이면 그 길이 문자열, Collection이면 그 크기 배열. */
    private static void putSized(ObjectNode body, String field, int len, boolean collection) {
        int n = Math.max(0, len);
        if (collection) {
            ArrayNode arr = Json.mapper().createArrayNode();
            for (int i = 0; i < n; i++) { arr.add("x"); }
            body.set(field, arr);
        } else {
            body.put(field, "a".repeat(n));
        }
    }

    /** regex와 매치되지 않는 첫 후보(Java Pattern.matches 검증). 모두 매치하면 null. */
    private static String patternMismatch(String regex) {
        for (String candidate : PATTERN_MISMATCH_CANDIDATES) {
            if (!Pattern.matches(regex, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 배열/컬렉션 타입 판정 — simple name 정확 매칭(예: "Listing"/"ResultSet" 오탐 방지). 제네릭 인자는 제거. */
    private static boolean isCollection(String javaType) {
        if (javaType == null) { return false; }
        if (javaType.endsWith("[]")) { return true; }
        String base = javaType;
        int lt = base.indexOf('<');
        if (lt >= 0) { base = base.substring(0, lt); }
        String simple = base.substring(base.lastIndexOf('.') + 1);
        return switch (simple) {
            case "List", "Set", "Collection", "Iterable",
                 "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet" -> true;
            default -> false;
        };
    }
}
