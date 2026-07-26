package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.JsonPaths;
import io.graphrag.builder.oracle.InputCandidates;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ProvenanceReport}의 가드 사실(GuardFact)을 출처별로 라우팅해 {@link TripleCandidate}(요청
 * body + seed SQL + stub mapping)를 합성한다 (REQ-005 코어 + REQ-006).
 *
 * <p><b>라우팅(REQ-005 코어):</b> 가드 피연산자의 {@link Origin}에 따라 INPUT은 {@code body}(JSON)에,
 * DB_READ는 seed {@code INSERT} 문장에, EXTERNAL_RESPONSE는 WireMock 스타일 stub mapping에 배치한다.
 *
 * <p><b>공동 배치·경계 만족값(REQ-006):</b>
 * <ul>
 *   <li>존재 가드({@code EXISTS}, 예: {@code repo.findById(req.id()).orElseThrow(...)}) — 코드 패턴상
 *       "성공하려면 반드시 존재"가 직접적인 요구사항이므로, 같은 값 s를 {@code body[jsonPath]}와 seed
 *       INSERT의 PK 컬럼에 동시 배치한다.</li>
 *   <li>비교 가드(예: {@code if (balance < amount) throw ...}) — {@code ProvenanceIndexer}는 "성공하면
 *       반드시 false여야 하는" throw 분기 조건을 그대로 {@code op}에 담으므로(가드 조건 op="&lt;"이면
 *       그 조건이 참일 때 예외), happy-path 후보는 op를 <b>부정(negate)</b>한 관계를 만족해야 한다
 *       (op="&lt;" → 필요 관계 "&gt;="). 부정 관계가 등가를 포함하면(GE/LE/EQ) 결정적 동치 값(예: 100=100)을
 *       우선한다({@link #satisfyingPair}).</li>
 *   <li>FK NOT NULL 부모 행 — seed 대상 테이블의 {@link TableSchema#foreignKeys()} 중 NOT NULL 컬럼에
 *       매핑된 FK는 부모 테이블 행도 재귀적으로 합성한다({@link #fillTable}).</li>
 * </ul>
 *
 * <p><b>이 task(8)의 명시적 확장 지점(후속 task 범위):</b> 갭 마커 문법(REQ-007), 후보 cap·우선순위
 * 정렬(REQ-033), stubs.json의 WireMock mapping 스키마 엄격 검증(REQ-008), {@code &&}/{@code ||} 등
 * 결합 논리 가드의 다중 피연산자 라우팅, {@link InputCandidates} DERIVED 해 배치. 현재는 단일
 * {@link TripleCandidate}만 반환한다 — cap/정렬은 Task 9에서 도입.
 */
public final class TripleSynthesizer {

    /** 비교 가드 boundary 만족값의 결정적 앵커(수치). 브리프 예시("GE면 col=input=100")를 그대로 채택. */
    private static final long NUMERIC_ANCHOR = 100L;

    private static final Set<String> NUMERIC_JAVA_TYPES = Set.of(
            "byte", "short", "int", "long", "float", "double",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.lang.Float", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger");

    /** 좌우 피연산자 관계(원본 소스 조건과 동일 극성). {@link #negate()}로 happy-path 관계를 얻는다. */
    private enum Rel {
        LT, LE, GT, GE, EQ, NE;

        static Rel fromSymbol(String op) {
            return switch (op) {
                case "<" -> LT;
                case "<=" -> LE;
                case ">" -> GT;
                case ">=" -> GE;
                case "==" -> EQ;
                case "!=" -> NE;
                default -> null;
            };
        }

        Rel negate() {
            return switch (this) {
                case LT -> GE;
                case LE -> GT;
                case GT -> LE;
                case GE -> LT;
                case EQ -> NE;
                case NE -> EQ;
            };
        }
    }

    /**
     * 리포트로부터 후보 트리플 목록을 합성한다. 현재는 결정적 단일 후보(cand-01 상당)만 반환한다 —
     * 후보 cap(기본 4)·우선순위 정렬(REQ-033)은 Task 9에서 이 메서드 위에 얹는다.
     *
     * @param report 분석 대상 엔드포인트의 provenance 리포트(가드/unguarded/unresolved)
     * @param shape  {@code @RequestBody} 타입의 필드 구조 (현재 라우팅은 가드 피연산자의 jsonPath만
     *               사용하므로 직접 참조하지 않지만, unguarded 필드의 body 배치를 추가할 후속 task의
     *               확장 지점으로 시그니처에 유지한다)
     * @param tables seed 대상 물리 스키마 목록(FK 부모 탐색 포함)
     * @param oracle DERIVED 출처 값 해(현재 미사용 — DERIVED 배치는 후속 task 확장 지점)
     */
    public List<TripleCandidate> synthesize(ProvenanceReport report, BodyShape shape,
                                            List<TableSchema> tables, InputCandidates oracle) {
        ObjectNode body = Json.mapper().createObjectNode();
        List<ObjectNode> stubMappings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, TableSchema> tablesByName = new LinkedHashMap<>();
        for (TableSchema t : tables) {
            tablesByName.put(t.name(), t);
        }
        // seed 대상 테이블별 컬럼→값 배정 (co-location: 같은 테이블에 여러 가드가 값을 보태면 한 행에 합쳐진다).
        Map<String, LinkedHashMap<String, Object>> rowsByTable = new LinkedHashMap<>();

        String primaryTable = solePrimaryTable(report);

        for (GuardFact guard : report.guards()) {
            switch (guard.op()) {
                case "EXISTS" -> routeExistsGuard(guard, primaryTable, tablesByName, rowsByTable, body, notes);
                case "<", "<=", ">", ">=", "==", "!=" ->
                        routeComparisonGuard(guard, tablesByName, rowsByTable, body, notes);
                case "!" -> routeNegatedEqualityGuard(guard, stubMappings, notes);
                default -> notes.add("op '" + guard.op() + "' at " + guard.at()
                        + " — 결합 논리/미지원 가드 라우팅은 후속 task 범위(확장 지점)");
            }
        }

        if (oracle != null && !oracle.numeric().isEmpty()) {
            // DERIVED 출처 배치의 확장 지점: 현재는 오라클 해를 소비하지 않고 존재만 기록한다.
            notes.add("InputCandidates 오라클 " + oracle.numeric().size()
                    + "개 필드 해 보유 — DERIVED 배치는 후속 task 확장 지점");
        }

        List<String> seedSqlStatements = finalizeSeedRows(rowsByTable, tablesByName, notes);

        return List.of(new TripleCandidate(body, seedSqlStatements, stubMappings, String.join("\n", notes)));
    }

    /** 리포트 전체에서 DB_READ 테이블이 정확히 하나뿐이면 그 테이블명을 반환(EXISTS의 table 미기재 시 폴백). */
    private static String solePrimaryTable(ProvenanceReport report) {
        Set<String> dbTables = new LinkedHashSet<>();
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() == Origin.DB_READ && v.table() != null) {
                    dbTables.add(v.table());
                }
            }
        }
        return dbTables.size() == 1 ? dbTables.iterator().next() : null;
    }

    /** EXISTS 가드: INPUT jsonPath 값 s를 body와 seed PK에 동시 배치. */
    private void routeExistsGuard(GuardFact guard, String primaryTable, Map<String, TableSchema> tablesByName,
                                  Map<String, LinkedHashMap<String, Object>> rowsByTable, ObjectNode body,
                                  List<String> notes) {
        for (ValueRef v : guard.operands()) {
            if (v.origin() != Origin.INPUT || v.jsonPath() == null) {
                continue;
            }
            String table = v.table() != null ? v.table() : primaryTable;
            TableSchema schema = table == null ? null : tablesByName.get(table);
            ColumnSchema pk = schema == null ? null : findPrimaryKey(schema);
            if (schema == null || pk == null) {
                notes.add("EXISTS(" + v.jsonPath() + ") at " + guard.at()
                        + " — 대상 테이블/PK 미해결, seed 배치 skip(확장 지점)");
                continue;
            }
            Object idValue = deterministicIdValue(v.jsonPath(), v.javaType(), pk);
            putBodyValue(body, v.jsonPath(), idValue);
            rowFor(rowsByTable, table).put(pk.name(), coerceForColumn(idValue, pk));
            notes.add("EXISTS(" + v.jsonPath() + ") at " + guard.at() + " -> body." + v.jsonPath()
                    + " = seed " + table + "." + pk.name() + " = " + idValue);
        }
    }

    /**
     * 비교 가드(DB_READ col OP INPUT, 또는 그 반대 순서): 추출된 op는 throw 분기의 원본 조건이므로
     * 부정 관계를 만족하는 (col값, body값) 쌍을 co-location한다.
     */
    private void routeComparisonGuard(GuardFact guard, Map<String, TableSchema> tablesByName,
                                      Map<String, LinkedHashMap<String, Object>> rowsByTable, ObjectNode body,
                                      List<String> notes) {
        List<ValueRef> operands = guard.operands();
        if (operands.size() != 2) {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — 피연산자 2개가 아닌 비교 가드는 미지원(확장 지점)");
            return;
        }
        ValueRef opA = operands.get(0);
        ValueRef opB = operands.get(1);
        ValueRef dbRef;
        ValueRef inputRef;
        if (opA.origin() == Origin.DB_READ && opB.origin() == Origin.INPUT) {
            dbRef = opA;
            inputRef = opB;
        } else if (opA.origin() == Origin.INPUT && opB.origin() == Origin.DB_READ) {
            dbRef = opB;
            inputRef = opA;
        } else {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — DB_READ/INPUT 조합이 아닌 비교 가드는 미지원(확장 지점)");
            return;
        }
        Rel rel = Rel.fromSymbol(guard.op());
        if (rel == null || dbRef.table() == null || dbRef.column() == null || inputRef.jsonPath() == null) {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — table/column/jsonPath 미해결, 비교 가드 skip(확장 지점)");
            return;
        }
        TableSchema schema = tablesByName.get(dbRef.table());
        ColumnSchema column = schema == null ? null
                : schema.columns().stream().filter(c -> c.name().equals(dbRef.column())).findFirst().orElse(null);

        Rel needed = rel.negate();
        boolean numeric = isNumericJavaType(dbRef.javaType()) || isNumericJavaType(inputRef.javaType());
        Object[] pair = satisfyingPair(needed, numeric);
        Object aVal = pair[0];
        Object bVal = pair[1];
        Object dbVal = dbRef == opA ? aVal : bVal;
        Object inputVal = inputRef == opA ? aVal : bVal;

        putBodyValue(body, inputRef.jsonPath(), inputVal);
        rowFor(rowsByTable, dbRef.table()).put(dbRef.column(), coerceForColumn(dbVal, column));
        notes.add("comparison(" + opA.origin() + " " + guard.op() + " " + opB.origin() + ") at " + guard.at()
                + " negated-to=" + needed + " -> body." + inputRef.jsonPath() + "=" + inputVal
                + ", seed " + dbRef.table() + "." + dbRef.column() + "=" + dbVal);
    }

    /**
     * {@code !x.equals(y)} 패턴(리터럴 vs EXTERNAL_RESPONSE)의 부정 등가 가드: happy path는 등가이므로
     * stubField에 리터럴 값을 그대로 채운 최소 stub mapping을 만든다. WireMock mapping 스키마의
     * 엄격한 정합(REQ-008)은 Task 9 범위 — 여기서는 request.method/urlPath + response.status/jsonBody의
     * 최소 형태만 만든다.
     */
    private void routeNegatedEqualityGuard(GuardFact guard, List<ObjectNode> stubMappings, List<String> notes) {
        ValueRef literalRef = null;
        ValueRef externalRef = null;
        for (ValueRef v : guard.operands()) {
            if (v.literal() != null) {
                literalRef = v;
            }
            if (v.origin() == Origin.EXTERNAL_RESPONSE) {
                externalRef = v;
            }
        }
        if (literalRef == null || externalRef == null || externalRef.callSite() == null
                || externalRef.stubField() == null) {
            notes.add("op '!' at " + guard.at() + " — EXTERNAL_RESPONSE 부정 등가 패턴이 아님, stub 라우팅 skip(확장 지점)");
            return;
        }
        String[] parts = externalRef.callSite().split(" ", 2);
        ObjectNode stub = Json.mapper().createObjectNode();
        ObjectNode request = stub.putObject("request");
        if (parts.length == 2) {
            request.put("method", parts[0]);
            request.put("urlPath", parts[1]);
        } else {
            request.put("urlPath", externalRef.callSite());
        }
        ObjectNode response = stub.putObject("response");
        response.put("status", 200);
        response.putObject("jsonBody").put(externalRef.stubField(), literalRef.literal());
        stubMappings.add(stub);
        notes.add("EXTERNAL_RESPONSE(" + externalRef.callSite() + ") at " + guard.at() + " -> stub."
                + externalRef.stubField() + "=" + literalRef.literal()
                + " (WireMock mapping 스키마 엄격 검증은 REQ-008/Task 9)");
    }

    // ---- seed 행 마무리(PK 기본값·FK 부모 재귀·NOT NULL 기본값) ----

    private final LinkedHashSet<String> emitOrder = new LinkedHashSet<>();

    private List<String> finalizeSeedRows(Map<String, LinkedHashMap<String, Object>> rowsByTable,
                                          Map<String, TableSchema> tablesByName, List<String> notes) {
        emitOrder.clear();
        List<String> initialTables = new ArrayList<>(rowsByTable.keySet());
        Set<String> visiting = new LinkedHashSet<>();
        for (String table : initialTables) {
            fillTable(table, tablesByName, rowsByTable, visiting, notes);
        }
        List<String> statements = new ArrayList<>();
        for (String table : emitOrder) {
            statements.add(toInsertStatement(table, rowsByTable.get(table)));
        }
        return statements;
    }

    /** 부모(FK 참조 테이블)를 먼저 재귀적으로 채운 뒤 자신을 {@link #emitOrder}에 등록(부모 선행 emission 보장). */
    private void fillTable(String tableName, Map<String, TableSchema> tablesByName,
                           Map<String, LinkedHashMap<String, Object>> rowsByTable, Set<String> visiting,
                           List<String> notes) {
        if (emitOrder.contains(tableName) || !visiting.add(tableName)) {
            return; // 이미 채움 완료 또는 순환 참조(방어적 종료)
        }
        LinkedHashMap<String, Object> row = rowFor(rowsByTable, tableName);
        TableSchema schema = tablesByName.get(tableName);
        if (schema == null) {
            notes.add("table '" + tableName + "' — 스키마 미제공, PK/FK 기본값 채움 skip(확장 지점)");
            emitOrder.add(tableName);
            visiting.remove(tableName);
            return;
        }
        ColumnSchema pk = findPrimaryKey(schema);
        if (pk != null && !row.containsKey(pk.name())) {
            row.put(pk.name(), deterministicIdValue(tableName, null, pk));
        }
        for (ColumnSchema column : schema.columns()) {
            if (column.primaryKey() || row.containsKey(column.name()) || column.nullable()) {
                continue;
            }
            ForeignKey fk = findForeignKey(column.name(), schema);
            if (fk != null) {
                fillTable(fk.referencedTable(), tablesByName, rowsByTable, visiting, notes);
                LinkedHashMap<String, Object> parentRow = rowsByTable.get(fk.referencedTable());
                Object parentPk = parentRow == null ? null : parentRow.get(fk.referencedColumn());
                row.put(column.name(), parentPk);
            } else {
                row.put(column.name(), defaultValueFor(column));
            }
        }
        emitOrder.add(tableName);
        visiting.remove(tableName);
    }

    private static LinkedHashMap<String, Object> rowFor(Map<String, LinkedHashMap<String, Object>> rowsByTable,
                                                         String table) {
        return rowsByTable.computeIfAbsent(table, t -> new LinkedHashMap<>());
    }

    private static ColumnSchema findPrimaryKey(TableSchema schema) {
        return schema.columns().stream().filter(ColumnSchema::primaryKey).findFirst().orElse(null);
    }

    private static ForeignKey findForeignKey(String columnName, TableSchema schema) {
        return schema.foreignKeys().stream().filter(fk -> fk.column().equals(columnName)).findFirst().orElse(null);
    }

    private static String toInsertStatement(String table, LinkedHashMap<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        StringBuilder values = new StringBuilder();
        boolean first = true;
        for (Object v : row.values()) {
            if (!first) {
                values.append(", ");
            }
            first = false;
            values.append(sqlLiteral(v));
        }
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ");";
    }

    private static String sqlLiteral(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    private static Object defaultValueFor(ColumnSchema column) {
        String type = column.jdbcType() == null ? "" : column.jdbcType().toUpperCase();
        if (type.contains("BOOL")) {
            return true;
        }
        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB")) {
            return "seed-" + column.name();
        }
        if (type.contains("DATE") || type.contains("TIME")) {
            return "2037-01-01"; // seed 문자열 리터럴(방언 무관 최소 표기) — 정밀 타입 변환은 후속 task
        }
        return 1;
    }

    // ---- 값 합성 유틸 ----

    private static boolean isNumericJavaType(String javaType) {
        return javaType != null && NUMERIC_JAVA_TYPES.contains(javaType);
    }

    /**
     * 필요 관계 {@code needed}를 만족하는 (좌, 우) 결정적 값 쌍. 등가를 포함하는 관계(GE/LE/EQ)는
     * 동치 값을 우선한다(브리프: "GE면 col=input=100 등 동치 우선"). 문자열 순서 비교(GT/LT)는
     * 미지원 — 보수적으로 동치 폴백(확장 지점).
     */
    private static Object[] satisfyingPair(Rel needed, boolean numeric) {
        if (numeric) {
            return switch (needed) {
                case GE, LE, EQ -> new Object[]{NUMERIC_ANCHOR, NUMERIC_ANCHOR};
                case GT -> new Object[]{NUMERIC_ANCHOR + 1, NUMERIC_ANCHOR};
                case LT -> new Object[]{NUMERIC_ANCHOR, NUMERIC_ANCHOR + 1};
                case NE -> new Object[]{NUMERIC_ANCHOR + 1, NUMERIC_ANCHOR};
            };
        }
        return switch (needed) {
            case NE -> new Object[]{"match", "mismatch"};
            default -> new Object[]{"match", "match"};
        };
    }

    /** jsonPath/javaType(+PK 컬럼 타입) 기준 결정적 id 값. body와 seed PK에 동일하게 쓰인다. */
    private static Object deterministicIdValue(String seedKey, String javaType, ColumnSchema pkColumn) {
        boolean numeric = isNumericJavaType(javaType)
                || (pkColumn.jdbcType() != null && pkColumn.jdbcType().toUpperCase().contains("INT"));
        if (numeric) {
            return NUMERIC_ANCHOR + Math.floorMod(seedKey.hashCode(), 900);
        }
        return "seed-" + seedKey.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase();
    }

    private static void putBodyValue(ObjectNode body, String jsonPath, Object value) {
        if (value instanceof Long l) {
            JsonPaths.putPath(body, jsonPath, l);
        } else if (value instanceof Integer i) {
            JsonPaths.putPath(body, jsonPath, i);
        } else if (value instanceof Double d) {
            JsonPaths.putPath(body, jsonPath, d);
        } else if (value instanceof Boolean b) {
            JsonPaths.putPath(body, jsonPath, b);
        } else {
            JsonPaths.putPath(body, jsonPath, String.valueOf(value));
        }
    }

    private static Object coerceForColumn(Object raw, ColumnSchema column) {
        if (column == null || raw == null) {
            return raw;
        }
        String type = column.jdbcType() == null ? "" : column.jdbcType().toUpperCase();
        if (type.contains("BIGINT")) {
            return toLong(raw);
        }
        if (type.contains("INT")) {
            return (int) toLong(raw);
        }
        if (type.contains("DECIMAL") || type.contains("NUMERIC")) {
            return new BigDecimal(raw.toString());
        }
        if (type.contains("DOUBLE") || type.contains("FLOAT") || type.contains("REAL")) {
            return Double.valueOf(raw.toString());
        }
        if (type.contains("BOOL")) {
            return raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
        }
        return raw.toString();
    }

    private static long toLong(Object raw) {
        return raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString());
    }
}
