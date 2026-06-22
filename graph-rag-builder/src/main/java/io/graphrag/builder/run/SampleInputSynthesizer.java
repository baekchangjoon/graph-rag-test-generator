package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.JsonPaths;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * endpoint body 타입에서 결정적 happy-path 입력을 합성한다 (Phase 0).
 * "<x>Id" 필드가 스키마 FK 컬럼과 매칭되면 부모 테이블에 probe row를 seed한다.
 * enum/날짜/이메일 필드는 유효 값으로 채워 SUT 역직렬화가 성공하게 한다 (Stage 0).
 * 시간/Random 사용 금지 — 동일 입력은 항상 동일 출력 (docs/04 결정성).
 */
public class SampleInputSynthesizer {

    private final Map<String, List<String>> enumConstants;

    public SampleInputSynthesizer() {
        this(Map.of());
    }

    public SampleInputSynthesizer(Map<String, List<String>> enumConstants) {
        this.enumConstants = enumConstants;
    }

    public SynthesizedInput synthesize(BodyShape shape, List<TableSchema> tables) {
        return synthesize(shape, tables, Map.of());
    }

    /**
     * Bean Validation 단일필드 제약(@Min/@Max/@Size/@Email/@Positive/@Negative)을 happy 값에 반영해
     * 핸들러의 검증 가드를 통과시킨다(Feature A). inter-field/수학 제약은 범위 밖 — enum은 첫 상수로 둔다.
     */
    public SynthesizedInput synthesize(BodyShape shape, List<TableSchema> tables,
                                       Map<String, List<FieldConstraint>> fieldConstraints) {
        if (shape.collection()) {
            // 컬렉션 요청 body는 happy-only로 1-element array를 합성한다(변이/네거티브 파이프라인은 가드됨).
            ArrayNode arr = Json.mapper().createArrayNode();
            List<SynthesizedInput.SeedRow> seeds = new ArrayList<>();
            if (shape.fields().isEmpty()) {                 // scalar/enum element
                arr.add(scalarValue(shape.javaType(), List.of()));
            } else {                                         // DTO element
                ObjResult el = synthesizeObject(shape, tables, fieldConstraints);
                arr.add(el.body());
                seeds.addAll(el.seeds());
            }
            return new SynthesizedInput(arr, seeds);
        }
        ObjResult o = synthesizeObject(shape, tables, fieldConstraints);
        return new SynthesizedInput(o.body(), o.seeds());
    }

    private record ObjResult(ObjectNode body, List<SynthesizedInput.SeedRow> seeds) {
    }

    private ObjResult synthesizeObject(BodyShape shape, List<TableSchema> tables,
                                       Map<String, List<FieldConstraint>> fieldConstraints) {
        ObjectNode body = Json.mapper().createObjectNode();
        List<SynthesizedInput.SeedRow> seeds = new ArrayList<>();

        for (BodyShape.BodyField field : shape.fields()) {
            boolean hasDot = field.name().contains(".");
            FkTarget fk = !hasDot && field.name().endsWith("Id") && field.name().length() > 2
                    ? findFkTarget(camelToSnake(field.name()), tables)
                    : null;
            if (fk != null) {
                String probeValue = "probe-" + field.name();
                body.put(field.name(), probeValue);
                seeds.add(seedRow(fk, probeValue, tables));
            } else if (hasDot) {
                putScalarNested(body, field, fieldConstraints.getOrDefault(field.name(), List.of()));
            } else {
                putScalar(body, field, fieldConstraints.getOrDefault(field.name(), List.of()));
            }
        }
        return new ObjResult(body, seeds);
    }

    private record FkTarget(String parentTable, String parentColumn) {
    }

    private FkTarget findFkTarget(String column, List<TableSchema> tables) {
        for (TableSchema table : tables) {
            for (ForeignKey fk : table.foreignKeys()) {
                if (fk.column().equals(column)) {
                    return new FkTarget(fk.referencedTable(), fk.referencedColumn());
                }
            }
        }
        return null;
    }

    /** 부모 테이블의 PK + NOT NULL 컬럼을 채운 seed row. */
    private SynthesizedInput.SeedRow seedRow(FkTarget fk, String probeValue, List<TableSchema> tables) {
        TableSchema parent = tables.stream()
                .filter(t -> t.name().equals(fk.parentTable()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("FK parent table not in schema: " + fk.parentTable()));

        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (ColumnSchema column : parent.columns()) {
            if (column.name().equals(fk.parentColumn())) {
                columns.add(column.name());
                values.add(probeValue);
            } else if (!column.nullable()) {
                columns.add(column.name());
                values.add(defaultFor(column));
            }
        }
        return new SynthesizedInput.SeedRow(parent.name(), columns, values);
    }

    private static Object defaultFor(ColumnSchema column) {
        String type = column.jdbcType().toUpperCase();
        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB")) {
            return "probe";
        }
        if (type.contains("BOOL")) {
            return true;
        }
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            return java.time.LocalDateTime.of(2037, 1, 1, 0, 0);
        }
        if (type.contains("DATE")) {
            return java.time.LocalDate.of(2037, 1, 1);
        }
        if (type.contains("TIME")) {
            return java.time.LocalTime.of(0, 0);
        }
        if (type.contains("UUID")) {
            return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
        return 1;
    }

    private static final Set<String> INT_TYPES = Set.of(
            "java.lang.Integer", "int", "java.lang.Long", "long", "java.lang.Short", "short");
    private static final Set<String> FLOAT_TYPES = Set.of(
            "java.lang.Double", "double", "java.lang.Float", "float", "java.math.BigDecimal");

    private void putScalar(ObjectNode body, BodyShape.BodyField field, List<FieldConstraint> cons) {
        body.set(field.name(), scalarValue(field.javaType(), cons, field.name()));
    }

    /** dot-path 필드(e.g. "address.city")를 JsonPaths.putPath로 중첩 materializ. */
    private void putScalarNested(ObjectNode body, BodyShape.BodyField field, List<FieldConstraint> cons) {
        JsonNode value = scalarValue(field.javaType(), cons, field.name());
        if (value.isLong() || value.isIntegralNumber()) {
            JsonPaths.putPath(body, field.name(), value.longValue());
        } else if (value.isDouble() || value.isFloatingPointNumber()) {
            JsonPaths.putPath(body, field.name(), value.doubleValue());
        } else {
            JsonPaths.putPath(body, field.name(), value.asText());
        }
    }

    /** 컬렉션 element 스칼라/enum 값 합성용 — 필드명 의존(email 폴백, sample-prefix)이 없는 진입점. */
    private JsonNode scalarValue(String javaType, List<FieldConstraint> cons) {
        return scalarValue(javaType, cons, null);
    }

    /**
     * 결정적 스칼라 Jackson 노드. fieldName!=null이면 필드명 기반 휴리스틱(email 추정, sample-prefix)을
     * 적용한다(object body 경로). null이면 element-only 경로로 타입만 본다.
     */
    private JsonNode scalarValue(String javaType, List<FieldConstraint> cons, String fieldName) {
        String t = javaType;
        if (INT_TYPES.contains(t)) { return LongNode.valueOf(boundedInt(cons)); }
        if (FLOAT_TYPES.contains(t)) { return DoubleNode.valueOf(boundedFloat(cons)); }
        if (t.equals("java.lang.Boolean") || t.equals("boolean")) { return BooleanNode.TRUE; }
        switch (t) {   // 시간 타입 — ISO-8601 문자열 (SUT Jackson이 string→LocalDate 역직렬화)
            case "java.time.LocalDate" -> { return TextNode.valueOf("2037-01-01"); }
            case "java.time.LocalDateTime" -> { return TextNode.valueOf("2037-01-01T00:00:00"); }
            case "java.time.LocalTime" -> { return TextNode.valueOf("00:00:00"); }
            case "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime" ->
                    { return TextNode.valueOf("2037-01-01T00:00:00Z"); }
            default -> { }
        }
        List<String> consts = enumConstants.get(t);
        if (consts == null) {   // simple-name 폴백 (noClasspath에서 javaType이 simple name일 수 있음)
            String simple = t.substring(t.lastIndexOf('.') + 1);
            consts = enumConstants.entrySet().stream()
                    .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        if (consts != null && !consts.isEmpty()) { return TextNode.valueOf(consts.get(0)); }
        boolean email = fieldName != null
                && (fieldName.toLowerCase().endsWith("email")
                    || cons.stream().anyMatch(c -> c.kind() == Kind.EMAIL));
        String base = email ? "probe@example.com"
                : (fieldName != null ? "sample-" + fieldName : "sample");
        return TextNode.valueOf(applySize(base, cons));
    }

    /** 정수 필드: MIN/MAX/POSITIVE/NEGATIVE 교집합 범위 내 결정적 값(기본 1 기준, 범위 충돌 시 하한 우선). */
    private static long boundedInt(List<FieldConstraint> cons) {
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
    private static double boundedFloat(List<FieldConstraint> cons) {
        boolean hasUpper = cons.stream().anyMatch(c ->
                c.kind() == Kind.MAX || c.kind() == Kind.NEGATIVE || c.kind() == Kind.NEGATIVE_OR_ZERO);
        // 상한 제약이 있으면 그 범위를 존중, 없으면 inter-field "must be large" 가드용으로 다소 큰
        // 기본값(과도하지 않게 — overflow/precision 리스크 최소화). 일반 inter-field는 Z3가 정공(Stage-4).
        return hasUpper ? (double) boundedInt(cons) : 1_000.0;
    }

    /** 문자열 필드: @Size(min,max) 를 만족하도록 padding/truncate. */
    private static String applySize(String s, List<FieldConstraint> cons) {
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

    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
