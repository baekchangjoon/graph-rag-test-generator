package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import io.graphrag.model.Json;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 형상(BodyShape)에서 결정적 minimal valid JSON을 합성한다 (REQ-006).
 * seed-row·table·FK 의존 없는 순수 값 합성 — SampleInputSynthesizer가 값 합성을 위임하고,
 * 외부 응답 stub 합성(ExternalStubSynthesizer)이 응답 형상에서 직접 쓴다.
 * 값 규칙: Integer→1, String→sample-&lt;field&gt;, enum→선언순 첫 상수, Boolean→true.
 * 시간/Random 사용 금지 — 동일 입력은 항상 동일 출력 (docs/04 결정성).
 */
public class ShapeJsonSynthesizer {

    /**
     * 응답 형상 합성 경로에서, 필드 타입이 스칼라/시간/enum 어디에도 안 맞는 해소 불가 객체 형상
     * (중첩 DTO 등)일 때 던진다. 호출부(ExternalStubSynthesizer.register)는 이를 silent String
     * 폴백으로 흡수하지 않고 unsynthesizable-shape loud-fail로 surface 한다 (REQ-010).
     */
    public static class UnsupportedShapeException extends RuntimeException {
        public UnsupportedShapeException(String message) {
            super(message);
        }
    }

    private final Map<String, List<String>> enumConstants;

    public ShapeJsonSynthesizer(Map<String, List<String>> enumConstants) {
        this.enumConstants = enumConstants;
    }

    /**
     * 형상→minimal JSON (응답 stub 합성 경로). collection이면 1-element array, 아니면 object/scalar.
     * 해소 불가 중첩 객체 형상이면 {@link UnsupportedShapeException}을 던진다(silent 금지, REQ-010).
     */
    public JsonNode synthesizeBody(BodyShape shape) {
        if (shape.collection()) {
            ArrayNode arr = Json.mapper().createArrayNode();
            if (shape.fields().isEmpty()) {
                arr.add(responseScalarValue(shape.javaType(), null));
            } else {
                arr.add(synthesizeObject(shape));
            }
            return arr;
        }
        if (shape.fields().isEmpty()) {
            return responseScalarValue(shape.javaType(), null);
        }
        return synthesizeObject(shape);
    }

    private ObjectNode synthesizeObject(BodyShape shape) {
        ObjectNode body = Json.mapper().createObjectNode();
        for (BodyShape.BodyField field : shape.fields()) {
            body.set(field.name(), responseScalarValue(field.javaType(), field.name()));
        }
        return body;
    }

    /**
     * 응답 합성 전용 스칼라 값. 타입이 스칼라/시간/enum 어디에도 해당 안 하는 객체 FQN(점 포함 + 알려진
     * 스칼라 아님 + enum 매칭 실패)이면 {@link UnsupportedShapeException}을 던진다. 그 외에는 입력 경로와
     * 동일한 {@link #scalarValue(String, List, String)}로 위임한다.
     */
    private JsonNode responseScalarValue(String javaType, String fieldName) {
        if (isUnresolvableObjectType(javaType)) {
            throw new UnsupportedShapeException("unsynthesizable nested object type: " + javaType);
        }
        return scalarValue(javaType, List.of(), fieldName);
    }

    /** 점 포함 FQN이면서 알려진 스칼라/시간도 아니고 enum(직접 또는 simple-name)으로도 못 푸는 타입. */
    private boolean isUnresolvableObjectType(String javaType) {
        if (INT_TYPES.contains(javaType) || FLOAT_TYPES.contains(javaType)) {
            return false;
        }
        if (!javaType.contains(".")) {
            return false;   // simple name(int/String 등)·합성 스칼라는 입력 경로와 동일하게 처리
        }
        switch (javaType) {
            case "java.lang.String", "java.lang.CharSequence", "java.lang.Boolean",
                    "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
                    "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime" -> {
                return false;
            }
            default -> { }
        }
        return resolveEnumConstants(javaType) == null;
    }

    private static final Set<String> INT_TYPES = Set.of(
            "java.lang.Integer", "Integer", "int",
            "java.lang.Long", "Long", "long",
            "java.lang.Short", "Short", "short");
    private static final Set<String> FLOAT_TYPES = Set.of(
            "java.lang.Double", "Double", "double",
            "java.lang.Float", "Float", "float",
            "java.math.BigDecimal", "BigDecimal");

    /** 컬렉션 element 스칼라/enum 값 합성용 — 필드명 의존(email 폴백, sample-prefix)이 없는 진입점. */
    public JsonNode scalarValue(String javaType, List<FieldConstraint> cons) {
        return scalarValue(javaType, cons, null);
    }

    /**
     * 결정적 스칼라 Jackson 노드. fieldName!=null이면 필드명 기반 휴리스틱(email 추정, sample-prefix)을
     * 적용한다(object body 경로). null이면 element-only 경로로 타입만 본다.
     */
    public JsonNode scalarValue(String javaType, List<FieldConstraint> cons, String fieldName) {
        String t = javaType;
        if (INT_TYPES.contains(t)) { return LongNode.valueOf(boundedInt(cons)); }
        if (FLOAT_TYPES.contains(t)) { return DoubleNode.valueOf(boundedFloat(cons)); }
        if (t.equals("java.lang.Boolean") || t.equals("Boolean") || t.equals("boolean")) {
            return BooleanNode.TRUE;   // 입력 동작 보존: Boolean happy 값은 true
        }
        switch (t) {   // 시간 타입 — ISO-8601 문자열 (SUT Jackson이 string→LocalDate 역직렬화)
            case "java.time.LocalDate" -> { return TextNode.valueOf("2037-01-01"); }
            case "java.time.LocalDateTime" -> { return TextNode.valueOf("2037-01-01T00:00:00"); }
            case "java.time.LocalTime" -> { return TextNode.valueOf("00:00:00"); }
            case "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime" ->
                    { return TextNode.valueOf("2037-01-01T00:00:00Z"); }
            default -> { }
        }
        List<String> consts = resolveEnumConstants(t);
        if (consts != null && !consts.isEmpty()) {
            return TextNode.valueOf(consts.get(0));   // 선언/등록순 첫 상수(결정적, 입력 동작 보존)
        }
        boolean email = fieldName != null
                && (fieldName.toLowerCase().endsWith("email")
                    || cons.stream().anyMatch(c -> c.kind() == Kind.EMAIL));
        String base = email ? "probe@example.com"
                : (fieldName != null ? "sample-" + fieldName : "sample");
        return TextNode.valueOf(applySize(base, cons));
    }

    /** enum 상수 목록 해석: 직접 FQN 매칭, 없으면 simple-name 폴백(noClasspath). 없으면 null. */
    private List<String> resolveEnumConstants(String javaType) {
        List<String> consts = enumConstants.get(javaType);
        if (consts != null) {
            return consts;
        }
        String simple = javaType.substring(javaType.lastIndexOf('.') + 1);
        return enumConstants.entrySet().stream()
                .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** 정수 필드: MIN/MAX/POSITIVE/NEGATIVE 교집합 범위 내 결정적 값(기본 1 기준, 범위 충돌 시 하한 우선). */
    static long boundedInt(List<FieldConstraint> cons) {
        long lower = Long.MIN_VALUE;
        long upper = Long.MAX_VALUE;
        for (FieldConstraint c : cons) {
            switch (c.kind()) {
                case MIN -> lower = Math.max(lower, c.numArg());
                case MAX -> upper = Math.min(upper, c.numArg());
                case POSITIVE -> lower = Math.max(lower, 1);
                case POSITIVE_OR_ZERO -> lower = Math.max(lower, 0);
                case NEGATIVE -> upper = Math.min(upper, -1);
                case NEGATIVE_OR_ZERO -> upper = Math.min(upper, 0);
                default -> { }
            }
        }
        // 범위 충돌(모순 제약, 흔히 비-가드 비교 혼입)은 안전하게 default로 흡수.
        if (lower > upper) {
            return 1;
        }
        return Math.min(Math.max(1, lower), upper);
    }

    /**
     * 실수 필드: 상한 제약(MAX/NEGATIVE)이 있으면 boundedInt로 캡, 없으면 "충분히 큰" 기본값.
     * inter-field "must be large" 가드(예: deposit*1.1 >= nights*rate)를 happy에서 통과시키기 위함
     * (단일필드 제약/비교가 상한을 주면 그 범위를 존중). 일반 amount/price 필드도 보통 무해.
     */
    static double boundedFloat(List<FieldConstraint> cons) {
        boolean hasUpper = cons.stream().anyMatch(c ->
                c.kind() == Kind.MAX || c.kind() == Kind.NEGATIVE || c.kind() == Kind.NEGATIVE_OR_ZERO);
        // 상한 제약이 있으면 그 범위를 존중, 없으면 inter-field "must be large" 가드용으로 다소 큰
        // 기본값(과도하지 않게 — overflow/precision 리스크 최소화). 일반 inter-field는 Z3가 정공(Stage-4).
        return hasUpper ? (double) boundedInt(cons) : 1_000.0;
    }

    /** 문자열 필드: @Size(min,max) 를 만족하도록 padding/truncate. */
    static String applySize(String s, List<FieldConstraint> cons) {
        int min = 0;
        int max = Integer.MAX_VALUE;
        for (FieldConstraint c : cons) {
            if (c.kind() == Kind.SIZE_MIN) { min = Math.max(min, (int) c.numArg()); }
            if (c.kind() == Kind.SIZE_MAX) { max = Math.min(max, (int) c.numArg()); }
        }
        if (s.length() > max) { s = s.substring(0, Math.max(0, max)); }
        if (s.length() < min) { s = (s + "a".repeat(min)).substring(0, min); }
        return s;
    }
}
