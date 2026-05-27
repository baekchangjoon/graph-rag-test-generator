package io.graphrag.builder.capture;

import io.graphrag.model.Binding;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.SourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실 발행 SQL과 바인딩 값을 {@link CapturedSql}로 변환.
 *
 * <p>Phase 0: SQL 타입 판별, 영향 테이블 best-effort 추출, 바인딩 origin은 모두 COMPUTED.
 * Phase 1+에서 dataflow 분석으로 API_PARAM/LITERAL 분류 강화.
 *
 * <p>Option A (docs/12): SELECT 의 row snapshot 결과를 함께 받는 오버로드 추가.
 */
public final class CapturedSqlBuilder {

    private static final Pattern TABLE_AFTER_VERB = Pattern.compile(
            "(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+([\\w.\"`]+)");

    private CapturedSqlBuilder() {}

    public static CapturedSql build(String pathId,
                                    String rawSql,
                                    List<?> parameterValues,
                                    CapturedSqlSource source) {
        return build(pathId, rawSql, parameterValues, source, List.of());
    }

    /**
     * Option A: SELECT 의 row snapshot (`readResultRows`) 까지 함께 기록.
     * 다른 SQL 타입에서는 빈 리스트 권장.
     */
    public static CapturedSql build(String pathId,
                                    String rawSql,
                                    List<?> parameterValues,
                                    CapturedSqlSource source,
                                    List<Map<String, Object>> readResultRows) {
        CapturedSqlType type = detectType(rawSql);
        List<Binding> bindings = new ArrayList<>(parameterValues.size());
        for (int i = 0; i < parameterValues.size(); i++) {
            bindings.add(new Binding(i, parameterValues.get(i), BindingOrigin.COMPUTED, null));
        }
        return new CapturedSql(
                "sql-" + UUID.randomUUID(),
                pathId,
                type,
                rawSql,
                bindings,
                source,
                new SourceLocation("unknown", "unknown", -1),
                extractTables(rawSql),
                List.of(),
                readResultRows);
    }

    /**
     * SQL 첫 토큰 기반 타입 판별. Listener 가 SELECT 분기 처리에 쓰기 위해 public.
     */
    public static CapturedSqlType detectType(String sql) {
        String trimmed = sql.stripLeading().toUpperCase();
        if (trimmed.startsWith("SELECT")) return CapturedSqlType.SELECT;
        if (trimmed.startsWith("INSERT")) return CapturedSqlType.INSERT;
        if (trimmed.startsWith("UPDATE")) return CapturedSqlType.UPDATE;
        if (trimmed.startsWith("DELETE")) return CapturedSqlType.DELETE;
        return CapturedSqlType.DDL;
    }

    private static List<String> extractTables(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher m = TABLE_AFTER_VERB.matcher(sql);
        while (m.find()) {
            String name = m.group(1).replace("\"", "").replace("`", "");
            if (!tables.contains(name)) {
                tables.add(name);
            }
        }
        return tables;
    }
}
