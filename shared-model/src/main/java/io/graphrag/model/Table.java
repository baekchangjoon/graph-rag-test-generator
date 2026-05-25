package io.graphrag.model;

import java.util.List;
import java.util.Objects;

/**
 * DB 테이블의 물리 스키마.
 *
 * @param scopeReachableVia testId 스코프로 cleanup 가능한지 — 어떤 API param으로 닿을 수 있는지.
 *                          빈 리스트면 글로벌 자원으로 간주, 도구 2가 경고 발생.
 */
public record Table(
        String name,
        List<Column> columns,
        List<String> primaryKey,
        List<ForeignKey> foreignKeys,
        List<List<String>> uniqueConstraints,
        List<String> checkConstraints,
        List<String> scopeReachableVia) {

    public Table {
        Objects.requireNonNull(name, "name");
        columns = List.copyOf(Objects.requireNonNullElse(columns, List.of()));
        primaryKey = List.copyOf(Objects.requireNonNullElse(primaryKey, List.of()));
        foreignKeys = List.copyOf(Objects.requireNonNullElse(foreignKeys, List.of()));
        uniqueConstraints = List.copyOf(Objects.requireNonNullElse(uniqueConstraints, List.of()));
        checkConstraints = List.copyOf(Objects.requireNonNullElse(checkConstraints, List.of()));
        scopeReachableVia = List.copyOf(Objects.requireNonNullElse(scopeReachableVia, List.of()));
    }
}
