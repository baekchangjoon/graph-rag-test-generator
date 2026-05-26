package io.graphrag.generator.result;

import java.util.List;
import java.util.Objects;

/**
 * 도구 2 합성 결과. SCHEMAS.md 2절 GenerationResult의 v1 (subset).
 *
 * <p>풀 스키마는 phase별 점진 확장. 현재 포함:
 * <ul>
 *   <li>status (SUCCESS|PARTIAL|FAILED)
 *   <li>newArtifacts (생성된 .java 파일들)
 *   <li>parallelSafety (socket session 부재 등 직렬 실행 권고)
 *   <li>rationale (path별 합성 근거)
 *   <li>recommendations (오케스트레이터를 위한 다음 단계 힌트)
 *   <li>diagnostics (텍스트 메시지들)
 * </ul>
 */
public record GenerationResult(
        Status status,
        List<NewArtifact> newArtifacts,
        List<ParallelSafetyEntry> parallelSafety,
        List<RationaleEntry> rationale,
        List<Recommendation> recommendations,
        List<String> diagnostics) {

    public GenerationResult {
        Objects.requireNonNull(status, "status");
        newArtifacts = List.copyOf(Objects.requireNonNullElse(newArtifacts, List.of()));
        parallelSafety = List.copyOf(Objects.requireNonNullElse(parallelSafety, List.of()));
        rationale = List.copyOf(Objects.requireNonNullElse(rationale, List.of()));
        recommendations = List.copyOf(Objects.requireNonNullElse(recommendations, List.of()));
        diagnostics = List.copyOf(Objects.requireNonNullElse(diagnostics, List.of()));
    }

    public enum Status { SUCCESS, PARTIAL, FAILED }

    public record NewArtifact(
            String path,
            String kind,
            List<String> coversPaths) {
        public NewArtifact {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(kind, "kind");
            coversPaths = List.copyOf(Objects.requireNonNullElse(coversPaths, List.of()));
        }
    }

    public record ParallelSafetyEntry(
            String test,
            String reason,
            String recommendation) {
        public ParallelSafetyEntry {
            Objects.requireNonNull(test, "test");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record RationaleEntry(
            String pathId,
            String whyChosen,
            List<String> capturedSources) {
        public RationaleEntry {
            Objects.requireNonNull(pathId, "pathId");
            capturedSources = List.copyOf(Objects.requireNonNullElse(capturedSources, List.of()));
        }
    }

    public record Recommendation(
            String kind,
            String target,
            String message) {
        public Recommendation {
            Objects.requireNonNull(kind, "kind");
        }
    }
}
