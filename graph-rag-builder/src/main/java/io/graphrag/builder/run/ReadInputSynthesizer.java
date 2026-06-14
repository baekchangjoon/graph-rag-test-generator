package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 조회(GET) 엔드포인트의 read-path 입력 + 시드를 결정적으로 합성한다.
 * path/query param을 WHERE 제약으로 보고, 타깃 테이블에 매칭 행을 시드한다.
 */
public class ReadInputSynthesizer {

    public SynthesizedInput synthesize(Endpoint endpoint, List<TableSchema> tables) {
        ObjectNode input = Json.mapper().createObjectNode();
        TableSchema target = resolveTargetTable(endpoint, tables);

        // FK 컬럼 → probe값 매핑 (parent 시드와 값 일치를 위해 먼저 결정)
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (target != null) {
            for (ColumnSchema column : target.columns()) {
                if (!column.nullable() || column.primaryKey()) {
                    ForeignKey fk = findFk(column.name(), target);
                    columns.add(column.name());
                    values.add(fk != null ? "probe-" + column.name() : defaultFor(column));
                }
            }
        }

        for (EndpointParam param : endpoint.params()) {
            if (param.kind() != ParamKind.PATH && param.kind() != ParamKind.QUERY) {
                continue;
            }
            String value = scalarFor(param);
            input.put(param.name(), value);
            String column = mapParamToColumn(param.name(), target);
            if (column != null) {
                int idx = columns.indexOf(column);
                if (idx >= 0) {
                    values.set(idx, value);
                } else {
                    columns.add(column);
                    values.add(value);
                }
            }
        }

        List<SynthesizedInput.SeedRow> seeds;
        if (target == null) {
            seeds = List.of();
        } else {
            List<SynthesizedInput.SeedRow> allSeeds = new ArrayList<>();
            // FK 부모 시드를 target 시드보다 먼저 수집 (parent-before-child)
            Set<String> visited = new HashSet<>();
            for (ForeignKey fk : target.foreignKeys()) {
                int fkIdx = columns.indexOf(fk.column());
                Object probeValue = fkIdx >= 0 ? values.get(fkIdx) : "probe-" + fk.column();
                seedParent(fk.referencedTable(), fk.referencedColumn(), probeValue, tables, visited, allSeeds);
            }
            allSeeds.add(new SynthesizedInput.SeedRow(target.name(), columns, values));
            seeds = allSeeds;
        }
        return new SynthesizedInput(input, seeds);
    }

    /** parentTable을 재귀적으로 시드하여 allSeeds에 추가 (사이클 방지: visited). */
    private void seedParent(String parentTable, String parentColumn, Object probeValue,
                            List<TableSchema> tables, Set<String> visited,
                            List<SynthesizedInput.SeedRow> allSeeds) {
        if (visited.contains(parentTable)) {
            return;
        }
        visited.add(parentTable);
        TableSchema parent = tables.stream()
                .filter(t -> t.name().equals(parentTable))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("FK parent table not in schema: " + parentTable));

        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (ColumnSchema col : parent.columns()) {
            if (col.name().equals(parentColumn)) {
                cols.add(col.name());
                vals.add(probeValue);
            } else if (!col.nullable()) {
                ForeignKey fk = findFk(col.name(), parent);
                if (fk != null) {
                    Object childProbe = "probe-" + col.name();
                    seedParent(fk.referencedTable(), fk.referencedColumn(), childProbe, tables, visited, allSeeds);
                    cols.add(col.name());
                    vals.add(childProbe);
                } else {
                    cols.add(col.name());
                    vals.add(defaultFor(col));
                }
            }
        }
        allSeeds.add(new SynthesizedInput.SeedRow(parent.name(), cols, vals));
    }

    /** target 테이블에서 column 이름에 해당하는 ForeignKey를 찾는다. */
    private static ForeignKey findFk(String columnName, TableSchema table) {
        return table.foreignKeys().stream()
                .filter(fk -> fk.column().equals(columnName))
                .findFirst()
                .orElse(null);
    }

    /** path 세그먼트/스키마로 타깃 테이블 추론: 경로에 테이블명(또는 단수형)이 등장하는 첫 매칭. */
    private TableSchema resolveTargetTable(Endpoint endpoint, List<TableSchema> tables) {
        String path = endpoint.path().toLowerCase();
        for (TableSchema table : tables) {
            String name = table.name().toLowerCase();
            if (path.contains("/" + name) || path.contains("/" + singular(name))) {
                return table;
            }
        }
        return null;
    }

    /** "id"/"xxxId" → PK 컬럼, 그 외 → snake_case 동일 컬럼이 있으면 그 컬럼. */
    private String mapParamToColumn(String paramName, TableSchema target) {
        if (target == null) {
            return null;
        }
        if (paramName.equals("id") || paramName.endsWith("Id")) {
            return target.columns().stream().filter(ColumnSchema::primaryKey)
                    .map(ColumnSchema::name).findFirst().orElse(null);
        }
        String snake = camelToSnake(paramName);
        return target.columns().stream().map(ColumnSchema::name)
                .filter(snake::equals).findFirst().orElse(null);
    }

    private static String scalarFor(EndpointParam param) {
        return switch (param.javaType()) {
            case "java.lang.Integer", "int", "java.lang.Long", "long" -> "1";
            default -> "probe-" + param.name();
        };
    }

    private static Object defaultFor(ColumnSchema column) {
        String type = column.jdbcType();
        if (type.contains("CHAR") || type.contains("TEXT")) return "probe";
        if (type.contains("BOOL")) return true;
        return 1;
    }

    private static String singular(String name) {
        return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
    }

    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
