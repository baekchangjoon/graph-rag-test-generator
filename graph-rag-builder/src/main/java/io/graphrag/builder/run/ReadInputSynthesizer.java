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
            // PK 컬럼을 먼저 삽입하여 index 0을 보장 (FixtureComposer의 DELETE 키)
            for (ColumnSchema column : target.columns()) {
                if (column.primaryKey()) {
                    ForeignKey fk = findFk(column.name(), target);
                    columns.add(column.name());
                    values.add(fk != null ? fkProbe(column) : defaultFor(column));
                }
            }
            for (ColumnSchema column : target.columns()) {
                if (!column.primaryKey() && !column.nullable()) {
                    ForeignKey fk = findFk(column.name(), target);
                    columns.add(column.name());
                    values.add(fk != null ? fkProbe(column) : defaultFor(column));
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
                // seed 값은 컬럼 JDBC 타입에 맞춰야 한다. 입력 JSON엔 문자열로 두되
                // (path/query는 어차피 텍스트), seed row에는 타입 일치 값을 넣는다
                // — bigint 컬럼에 varchar "1"을 넣으면 INSERT가 깨진다.
                Object seedValue = coerceForColumn(value, column, target);
                int idx = columns.indexOf(column);
                if (idx >= 0) {
                    values.set(idx, seedValue);
                } else {
                    columns.add(column);
                    values.add(seedValue);
                }
            }
        }

        List<SynthesizedInput.SeedRow> seeds;
        if (target == null) {
            seeds = List.of();
        } else {
            List<SynthesizedInput.SeedRow> allSeeds = new ArrayList<>();
            // FK 부모 시드를 target 시드보다 먼저 수집 (parent-before-child).
            // 시드 컬럼에 포함된 FK(= NOT NULL/PK)만 부모를 시드한다. nullable FK는
            // 자식 row에서 값이 없으므로(컬럼 미포함) 부모 행이 필요 없다.
            Set<String> visited = new HashSet<>();
            for (ForeignKey fk : target.foreignKeys()) {
                int fkIdx = columns.indexOf(fk.column());
                if (fkIdx < 0) {
                    continue;
                }
                seedParent(fk.referencedTable(), fk.referencedColumn(), values.get(fkIdx),
                        tables, visited, allSeeds);
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
        // parentColumn(= 참조된 PK)을 먼저 삽입하여 index 0을 보장 (FixtureComposer의 DELETE 키)
        cols.add(parentColumn);
        vals.add(probeValue);
        for (ColumnSchema col : parent.columns()) {
            if (col.name().equals(parentColumn)) {
                continue; // 이미 index 0에 삽입함
            } else if (!col.nullable()) {
                ForeignKey fk = findFk(col.name(), parent);
                if (fk != null) {
                    Object childProbe = fkProbe(col);
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

    /**
     * param → target 컬럼 매핑. 정확히 "id"인 param만 PK로 본다.
     * "userId" 같은 xxxId는 PK가 아니라 snake_case FK 컬럼(user_id)에 매핑해야 한다
     * (xxxId를 무조건 PK로 보면 varchar param을 bigint PK에 넣어 INSERT가 깨진다).
     */
    private String mapParamToColumn(String paramName, TableSchema target) {
        if (target == null) {
            return null;
        }
        if (paramName.equals("id")) {
            return target.columns().stream().filter(ColumnSchema::primaryKey)
                    .map(ColumnSchema::name).findFirst().orElse(null);
        }
        String snake = camelToSnake(paramName);
        return target.columns().stream().map(ColumnSchema::name)
                .filter(snake::equals).findFirst().orElse(null);
    }

    /** param의 문자열 값을 target 컬럼의 JDBC 타입에 맞는 seed 값으로 변환. */
    private static Object coerceForColumn(String value, String columnName, TableSchema target) {
        String jdbcType = target.columns().stream()
                .filter(c -> c.name().equals(columnName))
                .map(ColumnSchema::jdbcType)
                .findFirst().orElse("");
        String upper = jdbcType.toUpperCase();
        try {
            if (upper.contains("BIGINT")) return Long.parseLong(value);
            if (upper.contains("INT")) return Integer.parseInt(value);
            if (upper.contains("BOOL")) return Boolean.parseBoolean(value);
        } catch (NumberFormatException e) {
            return value;
        }
        return value;
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

    /**
     * FK 컬럼(= 참조된 부모 PK)의 JDBC 타입에 맞는 probe 값. 자식 FK와 부모 PK는
     * 같은 타입이므로 이 값을 양쪽에 동일하게 써서 FK 무결성을 만족시킨다.
     * 정수 PK(petclinic) 컬럼에 "probe-..." varchar를 넣으면 INSERT가 깨진다.
     */
    private static Object fkProbe(ColumnSchema fkColumn) {
        String type = fkColumn.jdbcType().toUpperCase();
        if (type.contains("CHAR") || type.contains("TEXT")) return "probe-" + fkColumn.name();
        if (type.contains("BIGINT")) return 1L;
        if (type.contains("INT")) return 1;
        if (type.contains("BOOL")) return true;
        return "probe-" + fkColumn.name();
    }

    private static String singular(String name) {
        return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
    }

    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
