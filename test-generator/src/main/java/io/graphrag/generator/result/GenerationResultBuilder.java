package io.graphrag.generator.result;

import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.PathContext;
import io.graphrag.model.CapturedSocketIO;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MultiPathSynthesisInput} + 작성된 파일 경로로부터 {@link GenerationResult}를 도출.
 *
 * <p>경고 / 권고를 자동 도출:
 * <ul>
 *   <li>Socket capture가 있고 session field가 비어있으면 SERIAL 권고
 *   <li>WS capture가 있는 path는 STOMP 환경변수 (WS_BASE_URI) 안내
 * </ul>
 */
public final class GenerationResultBuilder {

    private GenerationResultBuilder() {}

    public static GenerationResult build(MultiPathSynthesisInput input, Path writtenFile) {
        String className = writtenFile == null ? null : writtenFile.getFileName().toString();
        List<String> pathIds = input.paths().stream()
                .map(pc -> pc.path().id())
                .toList();

        List<GenerationResult.NewArtifact> artifacts = writtenFile == null
                ? List.of()
                : List.of(new GenerationResult.NewArtifact(
                        writtenFile.toString(), "TEST_CLASS", pathIds));

        List<GenerationResult.ParallelSafetyEntry> parallelSafety = new ArrayList<>();
        List<GenerationResult.RationaleEntry> rationale = new ArrayList<>();
        List<GenerationResult.Recommendation> recommendations = new ArrayList<>();

        for (PathContext pc : input.paths()) {
            // rationale 한 줄
            List<String> sources = new ArrayList<>();
            if (!pc.capturedSql().isEmpty()) sources.add("SQL(" + pc.capturedSql().size() + ")");
            if (!pc.capturedHttpCalls().isEmpty()) sources.add("HTTP(" + pc.capturedHttpCalls().size() + ")");
            if (!pc.capturedSocketIO().isEmpty()) sources.add("SOCKET(" + pc.capturedSocketIO().size() + ")");
            if (!pc.capturedWsMessages().isEmpty()) sources.add("WS(" + pc.capturedWsMessages().size() + ")");
            rationale.add(new GenerationResult.RationaleEntry(
                    pc.path().id(),
                    "path " + pc.path().id() + " (exit=" + pc.path().exitStatus() + ") composed from "
                            + (sources.isEmpty() ? "no captures" : String.join(", ", sources)),
                    sources));

            // socket 격리 점검
            for (CapturedSocketIO s : pc.capturedSocketIO()) {
                if (s.sessionField() == null || s.sessionField().isBlank()) {
                    parallelSafety.add(new GenerationResult.ParallelSafetyEntry(
                            "path_" + pc.path().id(),
                            "SOCKET_NO_SESSION",
                            "프로토콜에 session field가 없어 직렬 실행 필요 — @Execution(SAME_THREAD) 또는 mock 인스턴스 분리"));
                    break;
                }
            }
        }

        if (input.paths().stream().anyMatch(pc -> !pc.capturedWsMessages().isEmpty())) {
            recommendations.add(new GenerationResult.Recommendation(
                    "REQUIRE_WS_BASE_URI",
                    "env",
                    "환경변수 WS_BASE_URI 설정 필요 (예: ws://localhost:8080/ws)"));
        }
        if (input.paths().stream().anyMatch(pc -> !pc.capturedSocketIO().isEmpty())) {
            recommendations.add(new GenerationResult.Recommendation(
                    "REQUIRE_SOCKET_MOCK_ADMIN",
                    "env",
                    "환경변수 SOCKET_MOCK_ADMIN 설정 필요 (e.g. http://localhost:9099)"));
        }
        if (input.paths().stream().anyMatch(pc -> !pc.capturedHttpCalls().isEmpty())) {
            recommendations.add(new GenerationResult.Recommendation(
                    "REQUIRE_HTTP_MOCK_ADMIN",
                    "env",
                    "환경변수 HTTP_MOCK_ADMIN 설정 필요 (WireMock admin URL)"));
        }

        GenerationResult.Status status = writtenFile == null
                ? GenerationResult.Status.FAILED
                : (parallelSafety.isEmpty()
                        ? GenerationResult.Status.SUCCESS
                        : GenerationResult.Status.PARTIAL);

        return new GenerationResult(
                status, artifacts, parallelSafety, rationale,
                recommendations, List.of());
    }
}
