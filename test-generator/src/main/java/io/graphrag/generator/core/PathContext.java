package io.graphrag.generator.core;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.ExploredPath;

import java.util.List;
import java.util.Objects;

/**
 * 한 ExploredPath에 대해 합성에 필요한 컨텍스트.
 */
public record PathContext(ExploredPath path, List<CapturedSql> capturedSql) {
    public PathContext {
        Objects.requireNonNull(path, "path");
        capturedSql = List.copyOf(Objects.requireNonNullElse(capturedSql, List.of()));
    }
}
