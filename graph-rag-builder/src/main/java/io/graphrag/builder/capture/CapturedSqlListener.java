package io.graphrag.builder.capture;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * datasource-proxy의 QueryExecutionListener 구현체.
 *
 * <p>{@link CaptureContext#current()}가 활성일 때만 캡처. 활성이 아니면 noop.
 *
 * <p>{@link CapturedSqlSource}는 JPA_ENTITYMANAGER로 기본 설정. 실제 출처 식별은 Phase 1+에서
 * stack trace inspection 등으로 보강.
 *
 * <p>Option A (docs/12): SELECT 타입일 때 같은 Connection 으로 재실행하여 row snapshot 을
 * 함께 기록. fixture 합성기는 이 snapshot 으로 시드 데이터 INSERT 를 합성한다. snapshot
 * 실패는 silent fallback — 원본 query 동작에 영향 없음.
 */
public final class CapturedSqlListener implements QueryExecutionListener {

    private static final Pattern SELECT_FROM_WHERE = Pattern.compile(
            "(?is)^\\s*SELECT\\s+.*?\\s+FROM\\s+(\\S+)\\s+(?:AS\\s+\\S+\\s+)?WHERE\\s+(.*?)" +
                    "(?:\\s+ORDER\\s+BY\\b|\\s+LIMIT\\b|\\s+GROUP\\s+BY\\b|\\s*;?\\s*$)");

    /** snapshot row 상한 (1 SELECT 당). 큰 ResultSet 폭주 방지 */
    private static final int MAX_SNAPSHOT_ROWS = 100;

    private final CapturedSqlSource defaultSource;

    public CapturedSqlListener() {
        this(CapturedSqlSource.JPA_ENTITYMANAGER);
    }

    public CapturedSqlListener(CapturedSqlSource defaultSource) {
        this.defaultSource = defaultSource;
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) { /* noop */ }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        CaptureContext ctx = CaptureContext.current();
        if (ctx == null) return;
        for (QueryInfo qi : queryInfoList) {
            List<Object> params = flattenFirstBatch(qi);
            List<Map<String, Object>> rows = List.of();
            if (CapturedSqlBuilder.detectType(qi.getQuery()) == CapturedSqlType.SELECT) {
                rows = trySnapshotRows(execInfo, qi.getQuery(), params);
            }
            CapturedSql sql = CapturedSqlBuilder.build(
                    ctx.pathId(), qi.getQuery(), params, defaultSource, rows);
            ctx.addCapturedSql(sql);
        }
    }

    private static List<Object> flattenFirstBatch(QueryInfo qi) {
        List<Object> params = new ArrayList<>();
        if (qi.getParametersList() == null || qi.getParametersList().isEmpty()) {
            return params;
        }
        // batch 첫 번째만 사용 (단일 실행 가정). Phase 1+에서 batch 처리 강화.
        qi.getParametersList().get(0).forEach(p -> params.add(p.getArgs()[1]));
        return params;
    }

    /**
     * SELECT 를 같은 Connection 으로 재실행하여 row snapshot. 실패 시 빈 리스트 (fail-safe).
     *
     * <p>SELECT 의 컬럼 리스트가 부분집합인 경우 fixture 합성 시 NOT NULL 컬럼 누락 가능
     * → {@link #rebuildAsSelectStar} 로 가능한 한 `SELECT *` 로 변환.
     */
    List<Map<String, Object>> trySnapshotRows(ExecutionInfo info, String sql, List<Object> params) {
        try {
            Connection conn = info.getStatement().getConnection();
            if (conn == null || conn.isClosed()) return List.of();
            String snapshotSql = rebuildAsSelectStar(sql);
            try (PreparedStatement ps = conn.prepareStatement(snapshotSql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return readAllRows(rs);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
    }

    static String rebuildAsSelectStar(String sql) {
        Matcher m = SELECT_FROM_WHERE.matcher(sql);
        if (m.find()) {
            String table = m.group(1).replaceAll("[\"`]", "");
            String where = m.group(2).trim();
            // 단일 테이블 한정 — JOIN 의 ON 절은 정규식이 첫 FROM 만 잡으므로 매칭 안 됨
            if (!table.contains(",") && !table.contains(" ")) {
                return "SELECT * FROM " + table + " WHERE " + where;
            }
        }
        return sql;
    }

    private static List<Map<String, Object>> readAllRows(ResultSet rs) throws java.sql.SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> labels = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            labels.add(md.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < MAX_SNAPSHOT_ROWS) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < colCount; i++) {
                row.put(labels.get(i), rs.getObject(i + 1));
            }
            rows.add(row);
        }
        return rows;
    }
}
