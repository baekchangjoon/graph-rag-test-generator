package io.graphrag.model;

import java.util.List;

/** 분석 실행 중 SUT가 실제 발행한 SQL 한 건. */
public record CapturedSql(
        String id,
        String pathId,
        String sqlKind,
        String normalizedSql,
        String tableName,
        List<SqlBinding> bindings) {
}
