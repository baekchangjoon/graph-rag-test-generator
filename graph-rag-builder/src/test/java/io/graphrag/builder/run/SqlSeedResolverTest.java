package io.graphrag.builder.run;

import io.graphrag.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** pass-1 캡처 SQL → ResolutionHint 도출 규칙 검증. */
class SqlSeedResolverTest {

    private static final TableSchema MOOD_POINT = new TableSchema("mood_point",
            List.of(new ColumnSchema("id", "VARCHAR", false, true),
                    new ColumnSchema("user_id", "VARCHAR", false, false)),
            List.of(), List.of());
    private static final TableSchema GRAPH_RECORD = new TableSchema("graph_record",
            List.of(new ColumnSchema("diary_id", "VARCHAR", false, true),
                    new ColumnSchema("user_id", "VARCHAR", false, false)),
            List.of(), List.of());

    private static CapturedSql select(String table, String whereCol, String value, BindingOrigin origin) {
        List<SqlBinding> binds = whereCol == null ? List.of()
                : List.of(new SqlBinding(1, whereCol, value, origin, table));
        String sql = whereCol == null
                ? "select count(*) from " + table
                : "select x.* from " + table + " x where " + whereCol + "=?";
        return new CapturedSql("sql-1", "p1", "SELECT", sql, table, binds);
    }

    private static Endpoint getEndpoint(String paramName) {
        return new Endpoint("e", "GET", "/x/{" + paramName + "}", "x.C", "m",
                List.of(new EndpointParam(paramName, "java.lang.String", ParamKind.PATH)), false);
    }

    @Test
    void columnNameMatch_resolvesTableAndColumn() {
        // userId → user_id (camelToSnake 컬럼명 매칭) — 값 무관
        CapturedSql sql = select("mood_point", "user_id", "probe-userId-90042", BindingOrigin.API_PARAM);
        ResolutionHint hint = SqlSeedResolver.resolve(List.of(sql),
                Map.of("userId", "probe-userId-90042"), getEndpoint("userId"), List.of(MOOD_POINT));
        assertThat(hint).isNotNull();
        assertThat(hint.table()).isEqualTo("mood_point");
        assertThat(hint.paramColumn()).containsEntry("userId", "user_id");
    }

    @Test
    void valueMatch_fallbackWhenColumnNameDiffers() {
        // param 이름이 컬럼명과 snake-불일치 → 값 매칭 폴백 (origin=API_PARAM, value 동일)
        CapturedSql sql = select("mood_point", "user_id", "probe-uid-90042", BindingOrigin.API_PARAM);
        ResolutionHint hint = SqlSeedResolver.resolve(List.of(sql),
                Map.of("uid", "probe-uid-90042"), getEndpoint("uid"), List.of(MOOD_POINT));
        assertThat(hint).isNotNull();
        assertThat(hint.paramColumn()).containsEntry("uid", "user_id");
    }

    @Test
    void getGlobal_noWhere_tableOnlyEmptyParamColumn() {
        // WHERE 없는 집계: FROM mood_point 만, paramColumn 빈맵
        CapturedSql sql = select("mood_point", null, null, null);
        Endpoint e = new Endpoint("g", "GET", "/internal/analytics/global", "x.C", "g", List.of(), false);
        ResolutionHint hint = SqlSeedResolver.resolve(List.of(sql), Map.of(), e, List.of(MOOD_POINT));
        assertThat(hint).isNotNull();
        assertThat(hint.table()).isEqualTo("mood_point");
        assertThat(hint.paramColumn()).isEmpty();
    }

    @Test
    void graphRecord_pkColumn() {
        CapturedSql sql = select("graph_record", "diary_id", "probe-diaryId-91000", BindingOrigin.API_PARAM);
        ResolutionHint hint = SqlSeedResolver.resolve(List.of(sql),
                Map.of("diaryId", "probe-diaryId-91000"), getEndpoint("diaryId"), List.of(GRAPH_RECORD));
        assertThat(hint.table()).isEqualTo("graph_record");
        assertThat(hint.paramColumn()).containsEntry("diaryId", "diary_id");
    }

    @Test
    void noSelect_returnsNull() {
        // byUser(Redis): SELECT 없음 → null
        ResolutionHint hint = SqlSeedResolver.resolve(List.of(),
                Map.of("userId", "x"), getEndpoint("userId"), List.of(MOOD_POINT));
        assertThat(hint).isNull();
    }

    @Test
    void fromTableNotInSchema_excluded() {
        // FROM 테이블이 스키마에 없으면 후보에서 제외 → null
        CapturedSql sql = select("unknown_t", "user_id", "v", BindingOrigin.API_PARAM);
        ResolutionHint hint = SqlSeedResolver.resolve(List.of(sql),
                Map.of("userId", "v"), getEndpoint("userId"), List.of(MOOD_POINT));
        assertThat(hint).isNull();
    }

    @Test
    void prefersSelectWithParamMatch_overWhereless() {
        // 부수 집계 SELECT(WHERE 없음) + 주 조회 SELECT(param 매칭) 혼재 → 주 조회 우선
        CapturedSql aggregate = new CapturedSql("s1", "p1", "SELECT",
                "select count(*) from mood_point", "mood_point", List.of());
        CapturedSql main = new CapturedSql("s2", "p1", "SELECT",
                "select x.* from mood_point x where user_id=?", "mood_point",
                List.of(new SqlBinding(1, "user_id", "probe-userId-90042", BindingOrigin.API_PARAM, "mood_point")));
        ResolutionHint hint = SqlSeedResolver.resolve(List.of(aggregate, main),
                Map.of("userId", "probe-userId-90042"), getEndpoint("userId"), List.of(MOOD_POINT));
        assertThat(hint.paramColumn()).containsEntry("userId", "user_id");
    }
}
