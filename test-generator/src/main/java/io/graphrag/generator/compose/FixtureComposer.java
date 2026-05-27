package io.graphrag.generator.compose;

import io.graphrag.model.Binding;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * captured SQL 리스트로부터 픽스처 statement를 생성.
 *
 * <p>처리 매트릭스:
 * <ul>
 *   <li>INSERT — raw SQL 그대로 fixture (Phase 0)
 *   <li>SELECT + non-empty readResultRows — Option A (docs/12): snapshot 된 row 들을
 *       그대로 {@code INSERT INTO <table> (col1, col2, ...) VALUES (?, ?, ...)} 로 합성
 *   <li>SELECT empty rows / UPDATE / DELETE / DDL — 무시
 * </ul>
 *
 * <p>중복 dedup: 같은 (table, PK value) 조합으로 합성된 INSERT 는 한 번만. PK 는
 * 컬럼명 {@code id} 우선, 없으면 row 의 모든 컬럼+값을 키로 사용.
 */
public final class FixtureComposer {

    private FixtureComposer() {}

    public static List<FixtureStatement> fromCapturedSqls(List<CapturedSql> captured) {
        List<FixtureStatement> result = new ArrayList<>();
        Set<String> dedupKeys = new LinkedHashSet<>();   // table#pkval 키
        for (CapturedSql sql : captured) {
            if (sql.type() == CapturedSqlType.INSERT) {
                result.add(insertFromRawSql(sql));
            } else if (sql.type() == CapturedSqlType.SELECT && !sql.readResultRows().isEmpty()) {
                String table = sql.affectedTables().isEmpty() ? "unknown" : sql.affectedTables().get(0);
                for (Map<String, Object> row : sql.readResultRows()) {
                    String key = dedupKey(table, row);
                    if (!dedupKeys.add(key)) continue;   // 이미 합성됨
                    result.add(insertFromSnapshotRow(table, row));
                }
            }
        }
        return result;
    }

    public static List<FixtureStatement> cleanupFor(List<CapturedSql> inserted) {
        List<FixtureStatement> deletes = new ArrayList<>();
        Set<String> dedupKeys = new LinkedHashSet<>();
        for (CapturedSql sql : inserted) {
            if (sql.type() == CapturedSqlType.INSERT) {
                if (sql.affectedTables().isEmpty() || sql.bindings().isEmpty()) continue;
                String table = sql.affectedTables().get(0);
                Object key = sql.bindings().get(0).value();
                String keyCol = sql.affectedColumns().isEmpty() ? "id" : sql.affectedColumns().get(0);
                String dk = table + "#" + keyCol + "#" + key;
                if (!dedupKeys.add(dk)) continue;
                deletes.add(new FixtureStatement(
                        "DELETE FROM " + table + " WHERE " + keyCol + " = ?",
                        List.of(key), table));
            } else if (sql.type() == CapturedSqlType.SELECT && !sql.readResultRows().isEmpty()) {
                String table = sql.affectedTables().isEmpty() ? "unknown" : sql.affectedTables().get(0);
                for (Map<String, Object> row : sql.readResultRows()) {
                    String dk = dedupKey(table, row);
                    if (!dedupKeys.add(dk)) continue;
                    deletes.add(deleteFromSnapshotRow(table, row));
                }
            }
        }
        Collections.reverse(deletes);
        return deletes;
    }

    private static FixtureStatement insertFromRawSql(CapturedSql sql) {
        String table = sql.affectedTables().isEmpty() ? "unknown" : sql.affectedTables().get(0);
        return new FixtureStatement(
                sql.rawSql(),
                sql.bindings().stream().map(Binding::value).toList(),
                table);
    }

    private static FixtureStatement insertFromSnapshotRow(String table, Map<String, Object> row) {
        List<String> cols = new ArrayList<>(row.keySet());
        List<Object> vals = new ArrayList<>();
        for (String c : cols) vals.add(row.get(c));
        String placeholders = String.join(", ", Collections.nCopies(cols.size(), "?"));
        String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES (" + placeholders + ")";
        return new FixtureStatement(sql, vals, table);
    }

    private static FixtureStatement deleteFromSnapshotRow(String table, Map<String, Object> row) {
        // PK 추론: 컬럼명 'id' (case-insensitive) 우선
        String pkCol = findIdColumn(row);
        if (pkCol != null) {
            return new FixtureStatement(
                    "DELETE FROM " + table + " WHERE " + pkCol + " = ?",
                    List.of(row.get(pkCol)),
                    table);
        }
        // fallback: 모든 컬럼 AND (값 매칭). NULL 컬럼은 IS NULL 로 분기
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (i++ > 0) where.append(" AND ");
            if (e.getValue() == null) {
                where.append(e.getKey()).append(" IS NULL");
            } else {
                where.append(e.getKey()).append(" = ?");
                params.add(e.getValue());
            }
        }
        return new FixtureStatement(
                "DELETE FROM " + table + " WHERE " + where,
                params, table);
    }

    private static String findIdColumn(Map<String, Object> row) {
        for (String c : row.keySet()) {
            if ("id".equalsIgnoreCase(c)) return c;
        }
        return null;
    }

    private static String dedupKey(String table, Map<String, Object> row) {
        String pkCol = findIdColumn(row);
        if (pkCol != null) return table + "#" + pkCol + "#" + row.get(pkCol);
        // PK 없으면 전체 row 를 key 로
        Map<String, Object> sorted = new LinkedHashMap<>(row);
        return table + "#" + sorted;
    }
}
