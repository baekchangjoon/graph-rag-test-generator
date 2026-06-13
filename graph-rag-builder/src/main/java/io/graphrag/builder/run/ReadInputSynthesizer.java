package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * 조회(GET) 엔드포인트의 read-path 입력 + 시드를 결정적으로 합성한다.
 * path/query param을 WHERE 제약으로 보고, 타깃 테이블에 매칭 행을 시드한다.
 */
public class ReadInputSynthesizer {

    public SynthesizedInput synthesize(Endpoint endpoint, List<TableSchema> tables) {
        ObjectNode input = Json.mapper().createObjectNode();
        TableSchema target = resolveTargetTable(endpoint, tables);

        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (target != null) {
            for (ColumnSchema column : target.columns()) {
                if (!column.nullable() || column.primaryKey()) {
                    columns.add(column.name());
                    values.add(defaultFor(column));
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

        List<SynthesizedInput.SeedRow> seeds = target == null ? List.of()
                : List.of(new SynthesizedInput.SeedRow(target.name(), columns, values));
        return new SynthesizedInput(input, seeds);
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
