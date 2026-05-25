package io.graphrag.generator.compose;

import io.graphrag.model.Binding;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * captured SQL 리스트로부터 픽스처 statement를 생성.
 *
 * <p>Phase 0 단순화: INSERT만 그대로 픽스처로 변환. SELECT/UPDATE는 향후 phase에서 처리
 * (SELECT의 WHERE 조건에 맞는 사전 데이터 생성 등).
 */
public final class FixtureComposer {

    private FixtureComposer() {}

    public static List<FixtureStatement> fromCapturedSqls(List<CapturedSql> captured) {
        List<FixtureStatement> result = new ArrayList<>();
        for (CapturedSql sql : captured) {
            if (sql.type() != CapturedSqlType.INSERT) continue;
            String table = sql.affectedTables().isEmpty()
                    ? "unknown"
                    : sql.affectedTables().get(0);
            result.add(new FixtureStatement(
                    sql.rawSql(),
                    sql.bindings().stream().map(Binding::value).toList(),
                    table));
        }
        return result;
    }

    /**
     * captured SQL로부터 cleanup statement 생성. FK 역순 (자식 → 부모).
     *
     * <p>Phase 0 단순화: 발견된 INSERT의 역순. 실제 FK 그래프 기반 정밀 정렬은 phase 1+.
     */
    public static List<FixtureStatement> cleanupFor(List<CapturedSql> inserted) {
        List<FixtureStatement> deletes = new ArrayList<>();
        for (CapturedSql sql : inserted) {
            if (sql.type() != CapturedSqlType.INSERT) continue;
            if (sql.affectedTables().isEmpty()) continue;
            String table = sql.affectedTables().get(0);
            // 첫 번째 바인딩을 키로 가정 (Phase 0 단순화; phase 1+에서 PK 추적)
            if (sql.bindings().isEmpty()) continue;
            Object key = sql.bindings().get(0).value();
            String keyCol = sql.affectedColumns().isEmpty()
                    ? "id"
                    : sql.affectedColumns().get(0);
            deletes.add(new FixtureStatement(
                    "DELETE FROM " + table + " WHERE " + keyCol + " = ?",
                    List.of(key),
                    table));
        }
        // 역순 (FK 자식이 나중에 INSERT됐다는 가정)
        Collections.reverse(deletes);
        return deletes;
    }
}
