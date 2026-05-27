package io.graphrag.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 캡처된 SQL의 사실.
 *
 * <p>도구 1의 capture 레이어가 분석 시점에 발행된 실제 SQL을 기록한다.
 * 도구 2는 이 사실로부터 픽스처를 합성하고 ({@link #source()}별 전략),
 * 바인딩의 {@link Binding#origin()}으로 unique 치환 가능 여부를 판단한다.
 *
 * <p>{@link #readResultRows()} 는 Option A (docs/12) — SELECT 캡처 시 같은 Connection에서
 * 재실행하여 얻은 row snapshot. SELECT 이외에는 항상 빈 리스트. 시드 데이터를 INSERT
 * fixture 로 합성할 때 사용된다.
 */
public record CapturedSql(
        String id,
        String pathId,
        CapturedSqlType type,
        String rawSql,
        List<Binding> bindings,
        CapturedSqlSource source,
        SourceLocation sourceLocation,
        List<String> affectedTables,
        List<String> affectedColumns,
        List<Map<String, Object>> readResultRows) {

    public CapturedSql {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pathId, "pathId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(rawSql, "rawSql");
        Objects.requireNonNull(source, "source");
        bindings = List.copyOf(Objects.requireNonNullElse(bindings, List.of()));
        affectedTables = List.copyOf(Objects.requireNonNullElse(affectedTables, List.of()));
        affectedColumns = List.copyOf(Objects.requireNonNullElse(affectedColumns, List.of()));
        readResultRows = List.copyOf(Objects.requireNonNullElse(readResultRows, List.of()));
    }

    /**
     * Legacy convenience constructor (Option A 도입 이전 시그니처). 새 코드는 가능한
     * {@link #CapturedSql(String, String, CapturedSqlType, String, List,
     * CapturedSqlSource, SourceLocation, List, List, List)} 사용.
     */
    public CapturedSql(String id,
                       String pathId,
                       CapturedSqlType type,
                       String rawSql,
                       List<Binding> bindings,
                       CapturedSqlSource source,
                       SourceLocation sourceLocation,
                       List<String> affectedTables,
                       List<String> affectedColumns) {
        this(id, pathId, type, rawSql, bindings, source, sourceLocation,
                affectedTables, affectedColumns, List.of());
    }
}
