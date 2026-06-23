package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * endpoint body 타입에서 결정적 happy-path 입력을 합성한다 (Phase 0).
 * "<x>Id" 필드가 스키마 FK 컬럼과 매칭되면 부모 테이블에 probe row를 seed한다.
 * enum/날짜/이메일 필드는 유효 값으로 채워 SUT 역직렬화가 성공하게 한다 (Stage 0).
 * 시간/Random 사용 금지 — 동일 입력은 항상 동일 출력 (docs/04 결정성).
 */
public class SampleInputSynthesizer {

    private final ShapeJsonSynthesizer shapes;

    public SampleInputSynthesizer() {
        this(Map.of());
    }

    public SampleInputSynthesizer(Map<String, List<String>> enumConstants) {
        this.shapes = new ShapeJsonSynthesizer(enumConstants);
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
                arr.add(shapes.scalarValue(shape.javaType(), List.of()));
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

    private void putScalar(ObjectNode body, BodyShape.BodyField field, List<FieldConstraint> cons) {
        body.set(field.name(), shapes.scalarValue(field.javaType(), cons, field.name()));
    }

    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
