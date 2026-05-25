package io.graphrag.model;

import java.util.List;
import java.util.Objects;

/**
 * 테이블 외래키 관계.
 *
 * <p>FK 정렬에 사용: 도구 2의 픽스처 합성에서 부모 행을 먼저 INSERT.
 */
public record ForeignKey(
        List<String> fromColumns,
        String toTable,
        List<String> toColumns) {

    public ForeignKey {
        Objects.requireNonNull(toTable, "toTable");
        fromColumns = List.copyOf(Objects.requireNonNullElse(fromColumns, List.of()));
        toColumns = List.copyOf(Objects.requireNonNullElse(toColumns, List.of()));
    }
}
