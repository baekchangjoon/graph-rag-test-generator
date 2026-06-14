package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
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

    private final Map<String, List<String>> enumConstants;

    public SampleInputSynthesizer() {
        this(Map.of());
    }

    public SampleInputSynthesizer(Map<String, List<String>> enumConstants) {
        this.enumConstants = enumConstants;
    }

    public SynthesizedInput synthesize(BodyShape shape, List<TableSchema> tables) {
        ObjectNode body = Json.mapper().createObjectNode();
        List<SynthesizedInput.SeedRow> seeds = new ArrayList<>();

        for (BodyShape.BodyField field : shape.fields()) {
            FkTarget fk = field.name().endsWith("Id") && field.name().length() > 2
                    ? findFkTarget(camelToSnake(field.name()), tables)
                    : null;
            if (fk != null) {
                String probeValue = "probe-" + field.name();
                body.put(field.name(), probeValue);
                seeds.add(seedRow(fk, probeValue, tables));
            } else {
                putScalar(body, field);
            }
        }
        return new SynthesizedInput(body, seeds);
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

    private void putScalar(ObjectNode body, BodyShape.BodyField field) {
        String t = field.javaType();
        switch (t) {
            case "java.lang.Integer", "int", "java.lang.Long", "long",
                 "java.lang.Short", "short" -> { body.put(field.name(), 1); return; }
            case "java.lang.Double", "double", "java.lang.Float", "float",
                 "java.math.BigDecimal" -> { body.put(field.name(), 1.0); return; }
            case "java.lang.Boolean", "boolean" -> { body.put(field.name(), true); return; }
            default -> { }
        }
        switch (t) {   // 시간 타입 — ISO-8601 문자열 (SUT Jackson이 string→LocalDate 역직렬화)
            case "java.time.LocalDate" -> { body.put(field.name(), "2037-01-01"); return; }
            case "java.time.LocalDateTime" -> { body.put(field.name(), "2037-01-01T00:00:00"); return; }
            case "java.time.LocalTime" -> { body.put(field.name(), "00:00:00"); return; }
            case "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime" ->
                    { body.put(field.name(), "2037-01-01T00:00:00Z"); return; }
            default -> { }
        }
        List<String> consts = enumConstants.get(t);
        if (consts == null) {   // simple-name 폴백 (noClasspath에서 javaType이 simple name일 수 있음)
            String simple = t.substring(t.lastIndexOf('.') + 1);
            consts = enumConstants.entrySet().stream()
                    .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        if (consts != null && !consts.isEmpty()) { body.put(field.name(), consts.get(0)); return; }
        if (field.name().toLowerCase().endsWith("email")) { body.put(field.name(), "probe@example.com"); return; }
        body.put(field.name(), "sample-" + field.name());
    }

    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
