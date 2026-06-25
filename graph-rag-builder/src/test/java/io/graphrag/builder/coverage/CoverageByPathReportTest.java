package io.graphrag.builder.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CoverageByPathReportTest {

    private GraphAsset assetWithPath(String pathId, List<String> traceIds) {
        ExploredPath p = new ExploredPath(pathId, "ep", null, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Outcome.Kind.SUCCESS, 200, "200", traceIds);
        return new GraphAsset("sut1", "sha", List.of(), List.of(p), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), null, null, null);  // GraphAsset canonical 시그니처에 맞춤
    }

    @Test
    void mapsPathToExecRelativePaths(@TempDir Path out) throws Exception {     // REQ-005
        Path execDir = Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(execDir.resolve("x.exec"), "");
        Files.writeString(execDir.resolve("x.json"),
                "{\"testId\":\"x\",\"classCount\":5,\"status\":\"complete\",\"durationMs\":12,\"result\":\"passed\"}");
        CoverageByPathReport.write(assetWithPath("p1", List.of("x")), out);
        JsonNode r = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")));
        JsonNode ef = r.get("paths").get(0).get("execFiles").get(0);
        assertThat(ef.get("traceId").asText()).isEqualTo("x");
        assertThat(ef.get("exec").asText()).isEqualTo("work/pjacoco-exec/x.exec");
        assertThat(ef.get("sidecar").asText()).isEqualTo("work/pjacoco-exec/x.json");
        assertThat(r.get("paths").get(0).get("pathId").asText()).isEqualTo("p1");
    }

    @Test
    void projectsSidecarSummary(@TempDir Path out) throws Exception {          // REQ-006
        Path execDir = Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(execDir.resolve("x.exec"), "");
        Files.writeString(execDir.resolve("x.json"),
                "{\"testId\":\"x\",\"classCount\":5,\"status\":\"complete\",\"durationMs\":2065,\"result\":\"passed\"}");
        CoverageByPathReport.write(assetWithPath("p1", List.of("x")), out);
        JsonNode summary = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")))
                .get("paths").get(0).get("execFiles").get(0).get("summary");
        assertThat(summary.get("classCount").asInt()).isEqualTo(5);
        assertThat(summary.get("durationMs").asLong()).isEqualTo(2065);
        assertThat(summary.get("result").asText()).isEqualTo("passed");
    }

    @Test
    void missingSidecarYieldsNullSummaryNoThrow(@TempDir Path out) throws Exception {   // REQ-007
        Path execDir = Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(execDir.resolve("y.exec"), "");   // 사이드카 없음
        CoverageByPathReport.write(assetWithPath("p1", List.of("y")), out);
        JsonNode ef = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")))
                .get("paths").get(0).get("execFiles").get(0);
        assertThat(ef.get("summary").isNull()).isTrue();
    }

    @Test
    void malformedSidecarYieldsNullSummaryNoThrow(@TempDir Path out) throws Exception {  // REQ-007
        Path execDir = Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(execDir.resolve("w.exec"), "");
        Files.writeString(execDir.resolve("w.json"), "{ this is not json");
        assertThatCode(() -> CoverageByPathReport.write(assetWithPath("p1", List.of("w")), out))
                .doesNotThrowAnyException();
        JsonNode ef = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")))
                .get("paths").get(0).get("execFiles").get(0);
        assertThat(ef.get("summary").isNull()).isTrue();
    }

    @Test
    void emptyTraceIdsYieldEmptyExecFiles(@TempDir Path out) throws Exception {
        Files.createDirectories(out.resolve("work/pjacoco-exec"));
        CoverageByPathReport.write(assetWithPath("p1", List.of()), out);
        JsonNode ef = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")))
                .get("paths").get(0).get("execFiles");
        assertThat(ef).isEmpty();
    }
}
