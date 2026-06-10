package io.graphrag.model;

/** prepared statement 바인딩 1개. position은 1-base. table은 조인 별칭 해석 결과. */
public record SqlBinding(
        int position,
        String column,
        String value,
        BindingOrigin origin,
        String table) {

    public SqlBinding {
        table = table == null ? "" : table;
    }

    /** 이전 Phase 호환: table 미상 → 빈 값 (statement의 tableName으로 폴백). */
    public SqlBinding(int position, String column, String value, BindingOrigin origin) {
        this(position, column, value, origin, "");
    }
}
