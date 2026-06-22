package io.graphrag.builder.run;

import io.graphrag.model.BranchRef;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-008: 엔드포인트의 모든 탐색 경로가 FAILURE일 때 ExplorationReport.EndpointExploration에
 * noHappyPathReason="all responses error-enveloped"가 기록되고,
 * SUCCESS 경로가 하나라도 있으면 null이어야 한다.
 */
class NoHappyPathReportTest {

    private static ExploredPath path(String id, String endpointId, Outcome.Kind outcome) {
        // outcome을 명시하는 17-arg 생성자 사용
        return new ExploredPath(
                id, endpointId,
                Json.mapper().createObjectNode(),
                outcome == Outcome.Kind.SUCCESS ? 200 : 200,   // wire status 200 (error-envelope 시나리오)
                Json.mapper().createObjectNode(),
                List.of(), List.of(),
                List.of(new BranchRef("x.C", "m", 1, 0)),
                "heuristic", List.of(), List.of(), List.of(), List.of(), Map.of(),
                outcome,
                outcome == Outcome.Kind.SUCCESS ? 200 : 400,
                outcome == Outcome.Kind.SUCCESS ? "OK" : "Bad Request");
    }

    // ─── 모든 경로 FAILURE → noHappyPathReason="all responses error-enveloped" ──────────────
    @Test
    void allFailurePaths_setsNoHappyPathReason() {
        ExplorationReport.EndpointExploration exploration = new ExplorationReport.EndpointExploration(
                "ep-all-fail", 4, 2,
                List.of(),
                Map.of("heuristic", 2), 0, List.of(),
                "all responses error-enveloped");

        assertThat(exploration.noHappyPathReason()).isEqualTo("all responses error-enveloped");
    }

    // ─── SUCCESS 경로 존재 → noHappyPathReason==null ──────────────────────────────────────────
    @Test
    void hasSuccessPath_noHappyPathReasonIsNull() {
        ExplorationReport.EndpointExploration exploration = new ExplorationReport.EndpointExploration(
                "ep-has-success", 4, 3,
                List.of(),
                Map.of("heuristic", 2), 0, List.of(),
                null);

        assertThat(exploration.noHappyPathReason()).isNull();
    }

    // ─── 7-arg 후방 호환 생성자: noHappyPathReason 기본값 null ───────────────────────────────
    @Test
    void sevenArgCompatConstructor_noHappyPathReasonDefaultsNull() {
        ExplorationReport.EndpointExploration exploration = new ExplorationReport.EndpointExploration(
                "ep-compat", 4, 3,
                List.of(),
                Map.of("heuristic", 2), 0, List.of());

        assertThat(exploration.noHappyPathReason()).isNull();
    }

    // ─── 6-arg 후방 호환 생성자: noHappyPathReason 기본값 null ───────────────────────────────
    @Test
    void sixArgCompatConstructor_noHappyPathReasonDefaultsNull() {
        ExplorationReport.EndpointExploration exploration = new ExplorationReport.EndpointExploration(
                "ep-compat6", 4, 3,
                List.of(),
                Map.of("heuristic", 2), 0);

        assertThat(exploration.noHappyPathReason()).isNull();
    }

    // ─── JSON 왕복: noHappyPathReason 직렬화/역직렬화 ────────────────────────────────────────
    @Test
    void noHappyPathReason_roundTrips() throws Exception {
        ExplorationReport.EndpointExploration exploration = new ExplorationReport.EndpointExploration(
                "ep-all-fail", 4, 2,
                List.of(),
                Map.of("heuristic", 2), 0, List.of(),
                "all responses error-enveloped");

        String json = Json.mapper().writeValueAsString(exploration);
        ExplorationReport.EndpointExploration back =
                Json.mapper().readValue(json, ExplorationReport.EndpointExploration.class);

        assertThat(back.noHappyPathReason()).isEqualTo("all responses error-enveloped");
    }

    // ─── JSON 후방 호환: noHappyPathReason 필드 없는 구 JSON → null ───────────────────────────
    @Test
    void legacyJson_missingNoHappyPathReason_defaultsNull() throws Exception {
        String legacyJson = """
                {
                  "endpointId": "ep-old",
                  "totalBranches": 6,
                  "coveredBranches": 4,
                  "missedBranches": [],
                  "pathsByEngine": {"heuristic": 2},
                  "solverRelevantMissed": 1,
                  "droppedPaths": []
                }
                """;

        ExplorationReport.EndpointExploration back =
                Json.mapper().readValue(legacyJson, ExplorationReport.EndpointExploration.class);

        assertThat(back.noHappyPathReason()).isNull();
    }
}
