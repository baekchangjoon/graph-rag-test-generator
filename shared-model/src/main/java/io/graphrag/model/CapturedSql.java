package io.graphrag.model;

import java.util.List;
import java.util.Objects;

/**
 * 캡처된 SQL의 사실.
 *
 * <p>도구 1의 capture 레이어가 분석 시점에 발행된 실제 SQL을 기록한다.
 * 도구 2는 이 사실로부터 픽스처를 합성하고 ({@link #source()}별 전략),
 * 바인딩의 {@link Binding#origin()}으로 unique 치환 가능 여부를 판단한다.
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
        List<String> affectedColumns) {

    public CapturedSql {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pathId, "pathId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(rawSql, "rawSql");
        Objects.requireNonNull(source, "source");
        bindings = List.copyOf(Objects.requireNonNullElse(bindings, List.of()));
        affectedTables = List.copyOf(Objects.requireNonNullElse(affectedTables, List.of()));
        affectedColumns = List.copyOf(Objects.requireNonNullElse(affectedColumns, List.of()));
    }
}
