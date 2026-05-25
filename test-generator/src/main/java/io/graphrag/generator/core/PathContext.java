package io.graphrag.generator.core;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ExploredPath;

import java.util.List;
import java.util.Objects;

/**
 * 한 ExploredPath에 대해 합성에 필요한 컨텍스트.
 *
 * <p>Phase 1까지는 capturedSql만. Phase 2에서 capturedHttpCalls 추가.
 */
public record PathContext(
        ExploredPath path,
        List<CapturedSql> capturedSql,
        List<CapturedHttpCall> capturedHttpCalls) {

    public PathContext {
        Objects.requireNonNull(path, "path");
        capturedSql = List.copyOf(Objects.requireNonNullElse(capturedSql, List.of()));
        capturedHttpCalls = List.copyOf(Objects.requireNonNullElse(capturedHttpCalls, List.of()));
    }

    /** SQL만 있는 기존 호출자를 위한 호환 생성자. */
    public PathContext(ExploredPath path, List<CapturedSql> capturedSql) {
        this(path, capturedSql, List.of());
    }
}
