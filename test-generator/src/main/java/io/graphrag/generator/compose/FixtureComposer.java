package io.graphrag.generator.compose;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.SqlBinding;
import io.graphrag.model.TableSchema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 캡처 사실 + 스키마 → 픽스처/치환/검증 슬롯 합성 (docs/04 규칙 카탈로그).
 *
 * 규칙 요약:
 * - API_PARAM 바인딩 값이 PK/FK 컬럼에 닿는 body 필드 → testId 기반 unique 치환
 * - 캡처된 SELECT의 치환 대상 테이블 → 사전 INSERT (NOT NULL 채움)
 * - cleanup은 FK 역순(자식 먼저), 자기 스코프(WHERE key=?)만
 * - 응답 필드: LITERAL 바인딩과 값이 일치 → equalTo, 그 외 → notNullValue
 */
public class FixtureComposer {

    public ComposedFixture compose(ExploredPath path, List<CapturedSql> sqlList,
                                   List<TableSchema> tables) {
        return compose(path, sqlList, tables, List.of(), false);
    }

    public ComposedFixture compose(ExploredPath path, List<CapturedSql> sqlList,
                                   List<TableSchema> tables, List<RequiredSeed> seeds) {
        return compose(path, sqlList, tables, seeds, false);
    }

    /**
     * readPath=true(GET 엔드포인트)면 항상 read-path 합성을 쓴다. 2xx path는 RequiredSeed로
     * 시드하고, 비-2xx path(404/400)는 seeds가 비어 있어 시드 없이 요청+응답 단언만 만든다.
     * write-path(POST/PUT)의 SQL 바인딩 기반 시드 로직을 GET non-2xx에 잘못 적용하지 않게 한다.
     */
    public ComposedFixture compose(ExploredPath path, List<CapturedSql> sqlList,
                                   List<TableSchema> tables, List<RequiredSeed> seeds, boolean readPath) {
        return compose(path, sqlList, tables, seeds, readPath, java.util.Map.of());
    }

    /**
     * knownByField = 응답 필드명 → 결정적 기대값(요청/시드 유래). 응답 필드 X의 값이 knownByField[X]와
     * 같으면 그 필드는 입력/시드로 결정된 것 → equalTo. (flat value-set이 아니라 필드명 매칭 — 서버 생성
     * id가 우연히 입력 값과 같아도 오탐 안 함.)
     */
    public ComposedFixture compose(ExploredPath path, List<CapturedSql> sqlList,
                                   List<TableSchema> tables, List<RequiredSeed> seeds, boolean readPath,
                                   Map<String, String> knownByField) {
        if (readPath || !seeds.isEmpty()) {
            Map<String, TableSchema> seedTables = new HashMap<>();
            tables.forEach(t -> seedTables.put(t.name(), t));
            List<ComposedFixture.Stmt> seedInserts = seeds.stream()
                    .map(s -> new ComposedFixture.Stmt(
                            "INSERT INTO " + s.table() + " (" + String.join(", ", s.columns())
                                    + ") VALUES (" + String.join(", ", s.columns().stream().map(c -> "?").toList()) + ")",
                            renderSeedValues(s, seedTables)))
                    .toList();
            // seed는 부모→자식 순서로 INSERT되므로 cleanup DELETE는 역순(자식→부모)이어야
            // FK 제약을 위반하지 않는다.
            List<ComposedFixture.Stmt> seedDeletes = new ArrayList<>(seeds.stream()
                    .map(s -> new ComposedFixture.Stmt(
                            // columns[0] is the seed's key column (PK) — see EndpointExplorationRunner read-path convention
                            "DELETE FROM " + s.table() + " WHERE " + s.columns().get(0) + " = ?",
                            List.of(seedValueLiteral(s.values().get(0), s.table(),
                                    s.columns().get(0), seedTables))))
                    .toList());
            java.util.Collections.reverse(seedDeletes);
            return new ComposedFixture(List.of(), seedInserts, seedDeletes, "", List.of(),
                    assertionsFromResponse(path, sqlList, knownByField));
        }

        Map<String, TableSchema> tablesByName = new HashMap<>();
        tables.forEach(t -> tablesByName.put(t.name(), t));

        // 1. 치환 변수: body 필드 값이 PK/FK 컬럼의 API_PARAM 바인딩과 일치
        Map<String, ComposedFixture.Var> varsByFieldValue = new LinkedHashMap<>();
        // collection body(JSON 배열)면 원소 객체에서 vars/cleanup을 도출한다. scalar 배열/빈 배열은
        // 필드가 없어 vars 없음(정상). 객체면 그대로 사용.
        JsonNode sampleInput = path.sampleInput();
        JsonNode varSrc = (sampleInput != null && sampleInput.isArray() && sampleInput.size() > 0)
                ? sampleInput.get(0) : sampleInput;
        varSrc.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            if (!entry.getValue().isTextual()) {
                return;
            }
            String value = entry.getValue().asText();
            if (touchesKeyColumn(value, sqlList, tablesByName)) {
                varsByFieldValue.put(value, new ComposedFixture.Var(field,
                        "scope.testId() + \"-" + varSuffix(field) + "\""));
            }
        });

        // 2. 사전 INSERT: "조회가 성공했다"는 증거가 있는 SELECT만 seed한다.
        //    404류는 데이터 부재가 재현 조건이고(seed 금지), 409류는 존재가 전제다(seed 필요).
        List<SeededRow> seeded = new ArrayList<>();   // FK 부모 → 자식 순서
        Set<String> seenSeeds = new HashSet<>();
        List<DeleteTarget> deleteTargets = new ArrayList<>();
        for (int i = 0; i < sqlList.size(); i++) {
            CapturedSql sql = sqlList.get(i);
            for (SqlBinding binding : sql.bindings()) {
                ComposedFixture.Var var = varsByFieldValue.get(binding.value());
                if (var == null) {
                    continue;
                }
                if (sql.sqlKind().equals("SELECT") && lookupSucceeded(path, sqlList, i)) {
                    seedWithParents(tablesByName.get(bindingTable(sql, binding)), binding.column(),
                            var.name(), tablesByName, seeded, seenSeeds);
                }
                if (sql.sqlKind().equals("INSERT")) {
                    deleteTargets.add(new DeleteTarget(sql.tableName(), binding.column(), var.name()));
                }
            }
        }
        List<ComposedFixture.Stmt> inserts = seeded.stream()
                .map(row -> seedInsert(row.table(), row.keyColumn(), row.varName()))
                .toList();
        seeded.forEach(row -> deleteTargets.add(
                new DeleteTarget(row.table().name(), row.keyColumn(), row.varName())));

        // 3. cleanup: FK 깊이 내림차순 (자식 먼저)
        Map<String, Integer> depth = fkDepths(tables);
        List<ComposedFixture.Stmt> deletes = deleteTargets.stream()
                .distinct()
                .sorted(Comparator.comparing((DeleteTarget t) -> depth.getOrDefault(t.table(), 0))
                        .reversed()
                        .thenComparing(DeleteTarget::table))
                .map(t -> new ComposedFixture.Stmt(
                        "DELETE FROM " + t.table() + " WHERE " + t.column() + " = ?",
                        List.of(t.varName())))
                .toList();

        // 4. body 포맷: 치환 필드는 %s, 나머지는 sample 값 보존. collection body(배열)는 리터럴.
        List<String> bodyArgs = new ArrayList<>();
        String bodyFormat = (sampleInput != null && sampleInput.isArray())
                ? bodyFormatFor(sampleInput)
                : objectBodyFormat(sampleInput, varsByFieldValue, bodyArgs);

        return new ComposedFixture(
                new ArrayList<>(new LinkedHashSet<>(varsByFieldValue.values())),
                inserts, deletes, bodyFormat, bodyArgs,
                assertionsFromResponse(path, sqlList, knownByField));
    }

    /**
     * sampleInput → 요청 body 포맷(String.format 템플릿). 배열(collection body)이면 배열을 그대로
     * 리터럴 body로 직렬화한다(치환 슬롯/bodyArgs 없음 → '%'를 '%%'로 이스케이프). 객체면 기존
     * {...} 템플릿(치환 변수 없이)을 만든다.
     */
    public static String bodyFormatFor(JsonNode sampleInput) {
        if (sampleInput != null && sampleInput.isArray()) {
            return sampleInput.toString().replace("%", "%%");
        }
        return objectBodyFormat(sampleInput, java.util.Map.of(), new ArrayList<>());
    }

    /**
     * 객체 sampleInput → {...} body 템플릿. varsByFieldValue에 잡힌 필드는 "%s"로 치환 슬롯을 만들고
     * 해당 var 이름을 bodyArgs에 추가(out 파라미터), 나머지는 sample 값을 그대로 보존한다.
     */
    private static String objectBodyFormat(JsonNode sampleInput,
                                           Map<String, ComposedFixture.Var> varsByFieldValue,
                                           List<String> bodyArgs) {
        StringBuilder bodyFormat = new StringBuilder("{");
        var fields = sampleInput.fields();
        boolean first = true;
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!first) {
                bodyFormat.append(",");
            }
            first = false;
            bodyFormat.append("\"").append(entry.getKey()).append("\":");
            ComposedFixture.Var var = entry.getValue().isTextual()
                    ? varsByFieldValue.get(entry.getValue().asText()) : null;
            if (var != null) {
                bodyFormat.append("\"%s\"");
                bodyArgs.add(var.name());
            } else if (entry.getValue().isTextual()) {
                bodyFormat.append("\"").append(entry.getValue().asText()).append("\"");
            } else {
                bodyFormat.append(entry.getValue().toString());
            }
        }
        bodyFormat.append("}");
        return bodyFormat.toString();
    }

    private static List<ComposedFixture.Assertion> assertionsFromResponse(ExploredPath path,
                                                                           List<CapturedSql> sqlList,
                                                                           Map<String, String> knownByField) {
        // 서버가 SQL에 literal로 쓴 값(예: status='PENDING')은 필드 무관하게 결정적.
        Set<String> literalValues = new HashSet<>();
        sqlList.forEach(sql -> sql.bindings().stream()
                .filter(b -> b.origin() == BindingOrigin.LITERAL)
                .forEach(b -> literalValues.add(b.value())));
        List<ComposedFixture.Assertion> assertions = new ArrayList<>();
        if (path.sampleResponse() == null || path.sampleResponse().isNull()) {
            return assertions;
        }
        path.sampleResponse().fields().forEachRemaining(entry -> {
            JsonNode v = entry.getValue();
            if (v.isNull()) {
                return;
            }
            String value = v.asText();
            // 응답 필드 X의 값이 (a)같은 이름 입력/시드 필드 값과 일치하거나 (b)SQL literal이면 결정적 →
            // equalTo. 서버 생성(시퀀스 id/count/timestamp)은 둘 다 아님 → notNull. 필드명 매칭이라
            // 우연히 같은 값(id=1 vs amount=1)으로 인한 오탐 없음.
            boolean concrete = (value.equals(knownByField.get(entry.getKey())) || literalValues.contains(value))
                    && !looksServerGenerated(value);
            String matcher;
            if (!concrete) {
                matcher = "notNullValue()";
            } else if (v.isIntegralNumber() || v.isBoolean()) {
                matcher = "equalTo(" + value + ")";   // 숫자/불리언: 따옴표 없이(Integer/Boolean 매칭)
            } else if (v.isTextual()) {
                matcher = "equalTo(\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\")";
            } else {
                matcher = "notNullValue()";   // 실수/객체/배열: RestAssured 수치 매칭 불안정 → 보수적
            }
            assertions.add(new ComposedFixture.Assertion(entry.getKey(), matcher));
        });
        return assertions;
    }

    private static final java.util.regex.Pattern UUID_RE = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final java.util.regex.Pattern TIMESTAMP_RE = java.util.regex.Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}.*");

    /** UUID/ISO-타임스탬프처럼 매 요청 달라지는 서버 생성 값인지. */
    private static boolean looksServerGenerated(String value) {
        return UUID_RE.matcher(value).matches() || TIMESTAMP_RE.matcher(value).matches();
    }

    private record DeleteTarget(String table, String column, String varName) {
    }

    private record SeededRow(TableSchema table, String keyColumn, String varName) {
    }

    /**
     * 캡처 시점에 이 SELECT가 행을 찾았는지의 증거:
     * (a) 이후 다른 SQL이 이어졌다 (실행이 계속됨)
     * (b) path에 외부 HTTP 호출이 있다 (조회 통과 후 진행)
     * (c) 둘 다 없으면 응답이 2xx일 때만 성공으로 본다 (마지막 문장이 SELECT인 happy)
     */
    private static boolean lookupSucceeded(ExploredPath path, List<CapturedSql> sqlList,
                                           int selectIndex) {
        if (selectIndex < sqlList.size() - 1) {
            return true;
        }
        if (!path.capturedHttpCallIds().isEmpty()) {
            return true;
        }
        return path.expectedStatus() / 100 == 2;
    }

    /** 자식 테이블 seed 전에 같은 var 값으로 FK 부모 행을 재귀적으로 seed한다. */
    private static void seedWithParents(TableSchema table, String keyColumn, String varName,
                                        Map<String, TableSchema> tables,
                                        List<SeededRow> seeded, Set<String> seen) {
        if (table == null || !seen.add(table.name() + "|" + keyColumn)) {
            return;
        }
        for (var fk : table.foreignKeys()) {
            if (fk.column().equals(keyColumn)) {
                seedWithParents(tables.get(fk.referencedTable()), fk.referencedColumn(),
                        varName, tables, seeded, seen);
            }
        }
        seeded.add(new SeededRow(table, keyColumn, varName));
    }

    /** 조인 별칭 해석된 바인딩 테이블 (없으면 statement의 주 테이블). */
    private static String bindingTable(CapturedSql sql, SqlBinding binding) {
        return binding.table().isEmpty() ? sql.tableName() : binding.table();
    }

    private static boolean touchesKeyColumn(String value, List<CapturedSql> sqlList,
                                            Map<String, TableSchema> tables) {
        for (CapturedSql sql : sqlList) {
            for (SqlBinding binding : sql.bindings()) {
                if (binding.origin() != BindingOrigin.API_PARAM
                        || !binding.value().equals(value)) {
                    continue;
                }
                TableSchema table = tables.get(bindingTable(sql, binding));
                if (table == null) {
                    continue;
                }
                boolean isPk = table.columns().stream()
                        .anyMatch(c -> c.name().equals(binding.column()) && c.primaryKey());
                boolean isFk = table.foreignKeys().stream()
                        .anyMatch(fk -> fk.column().equals(binding.column()));
                if (isPk || isFk) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ComposedFixture.Stmt seedInsert(TableSchema table, String keyColumn,
                                                   String varName) {
        List<String> columns = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (ColumnSchema column : table.columns()) {
            if (column.name().equals(keyColumn)) {
                columns.add(column.name());
                args.add(varName);
            } else if (isAutoGenerated(column)) {
                // SERIAL/IDENTITY PK는 DB가 채운다 — 고정값 INSERT는 충돌 유발
                continue;
            } else if (!column.nullable()) {
                columns.add(column.name());
                args.add(defaultExprFor(column));
            }
        }
        String sql = "INSERT INTO " + table.name() + " (" + String.join(", ", columns)
                + ") VALUES (" + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
        return new ComposedFixture.Stmt(sql, args);
    }

    private static boolean isAutoGenerated(ColumnSchema column) {
        // DB가 채우는 PK는 고정값 INSERT 금지(병렬 시 IDENTITY 생성값과 충돌). JDBC
        // IS_AUTOINCREMENT(autoIncrement)가 1차 신호. postgres GENERATED BY DEFAULT는
        // TYPE_NAME에 SERIAL/IDENTITY가 안 나오므로 문자열 휴리스틱만으론 놓친다.
        return column.primaryKey()
                && (column.autoIncrement()
                    || column.jdbcType().contains("SERIAL") || column.jdbcType().contains("IDENTITY"));
    }

    private static String defaultExprFor(ColumnSchema column) {
        String type = column.jdbcType().toUpperCase();
        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB")) {
            return "\"probe\"";
        }
        if (type.contains("BOOL")) {
            return "true";
        }
        // 시간 타입: FQN java.time을 코드에 그대로 (import 불필요), setObject가 DATE/TIMESTAMP에 바인딩
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            return "java.time.LocalDateTime.of(2037, 1, 1, 0, 0)";
        }
        if (type.contains("DATE")) {
            return "java.time.LocalDate.of(2037, 1, 1)";
        }
        if (type.contains("TIME")) {
            return "java.time.LocalTime.of(0, 0)";
        }
        if (type.contains("UUID")) {
            return "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000001\")";
        }
        return "1";
    }

    /** 테이블별 FK 깊이 (부모 0, 자식 = max(부모)+1). 순환은 보수적으로 0. */
    private static Map<String, Integer> fkDepths(List<TableSchema> tables) {
        Map<String, Integer> depth = new HashMap<>();
        for (TableSchema table : tables) {
            computeDepth(table.name(), tables, depth, new HashSet<>());
        }
        return depth;
    }

    private static int computeDepth(String name, List<TableSchema> tables,
                                    Map<String, Integer> depth, Set<String> visiting) {
        if (depth.containsKey(name)) {
            return depth.get(name);
        }
        if (!visiting.add(name)) {
            return 0;
        }
        int result = tables.stream()
                .filter(t -> t.name().equals(name))
                .flatMap(t -> t.foreignKeys().stream())
                .filter(fk -> !fk.referencedTable().equals(name))
                .mapToInt(fk -> computeDepth(fk.referencedTable(), tables, depth, visiting) + 1)
                .max().orElse(0);
        depth.put(name, result);
        return result;
    }

    private static String javaStringLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<String> renderSeedValues(RequiredSeed seed,
                                                 Map<String, TableSchema> tablesByName) {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < seed.values().size(); i++) {
            args.add(seedValueLiteral(seed.values().get(i), seed.table(),
                    seed.columns().get(i), tablesByName));
        }
        return args;
    }

    /**
     * seed 값을 컬럼 JDBC 타입에 맞는 Java 리터럴로 렌더링한다. 숫자/불리언 컬럼에
     * 따옴표 친 문자열을 넣으면 setObject가 varchar로 바인딩해 INSERT가 깨지므로
     * 정수/불리언은 따옴표 없이 emit한다.
     */
    private static String seedValueLiteral(String value, String table, String column,
                                           Map<String, TableSchema> tablesByName) {
        TableSchema schema = tablesByName.get(table);
        String jdbcType = schema == null ? "" : schema.columns().stream()
                .filter(c -> c.name().equals(column))
                .map(ColumnSchema::jdbcType)
                .findFirst().orElse("");
        String upper = jdbcType.toUpperCase();
        try {
            if (upper.contains("INT")) {
                return upper.contains("BIGINT")
                        ? Long.toString(Long.parseLong(value)) + "L"
                        : Integer.toString(Integer.parseInt(value));
            }
            if (upper.contains("BOOL")) {
                return Boolean.toString(Boolean.parseBoolean(value));
            }
            // 시간/수치 타입: java.time FQN parse / BigDecimal로 emit (setObject가 올바른 타입 바인딩).
            // 따옴표 문자열로 두면 numeric/date 컬럼 INSERT가 깨진다 (by-id 리소스 시드 재현).
            if (upper.contains("TIMESTAMP") || upper.contains("DATETIME")) {
                return "java.time.LocalDateTime.parse(" + javaStringLiteral(value) + ")";
            }
            if (upper.contains("DATE")) {
                return "java.time.LocalDate.parse(" + javaStringLiteral(value) + ")";
            }
            if (upper.contains("TIME")) {
                return "java.time.LocalTime.parse(" + javaStringLiteral(value) + ")";
            }
            if (upper.contains("UUID")) {
                return "java.util.UUID.fromString(" + javaStringLiteral(value) + ")";
            }
            if (upper.contains("NUMERIC") || upper.contains("DECIMAL") || upper.contains("NUMBER")
                    || upper.contains("DOUBLE") || upper.contains("REAL") || upper.contains("FLOAT")) {
                return "new java.math.BigDecimal(" + javaStringLiteral(value) + ")";
            }
        } catch (NumberFormatException e) {
            return javaStringLiteral(value);
        }
        return javaStringLiteral(value);
    }

    private static String varSuffix(String fieldName) {
        String base = fieldName.endsWith("Id") && fieldName.length() > 2
                ? fieldName.substring(0, fieldName.length() - 2)
                : fieldName;
        return base.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
