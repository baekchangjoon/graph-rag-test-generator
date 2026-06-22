package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-009: RC-B — FAILURE 피드백 pass-2 재시드(예산 N=4).
 *
 * <p>RC-B의 결정 로직(budgeted-retry)을 순수 단위로 검증한다. {@code run()} 전체를 구동하려면 실
 * DB connection·coverage·HTTP invoker가 필요해 과중하므로, 예산-재시도 결정을 인코딩하는 가장 작은
 * 단위 — {@link EndpointExplorationRunner#shouldRetryPass2(List, int)} 와 그 위에 얹은 시뮬레이션
 * 루프 — 를 테스트한다. 실제 러너의 pass-2 루프는 동일한 술어로 종료/지속을 결정한다.
 */
@DisplayName("REQ-009: RC-B FAILURE 피드백 pass-2 재시드(예산 4)")
class RcbRetryLoopTest {

    private static JsonNode obj() {
        return Json.mapper().createObjectNode();
    }

    /** outcome 단일 path 번들을 흉내 낸 ExploredPath 리스트. */
    private static List<ExploredPath> pathsWith(Outcome.Kind kind) {
        int status = kind == Outcome.Kind.SUCCESS ? 200 : 404;
        return List.of(new ExploredPath("p", "ep", obj(), status, obj(),
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(),
                List.of(), List.of(), java.util.Map.of(), kind, status, String.valueOf(status)));
    }

    /**
     * pass-2 재시드 루프를 RC-B 결정 술어로 구동한 시뮬레이션. {@code reExplore.apply(attempt)} 는
     * 각 시도의 결과 번들을 반환한다. 반환: 실행된 pass-2 시도 횟수.
     */
    private static int simulate(IntFunction<List<ExploredPath>> reExplore) {
        int attempt = 0;
        List<ExploredPath> bundle = reExplore.apply(attempt);   // pass-2 최초 시도(기존 동작)
        attempt++;
        while (EndpointExplorationRunner.shouldRetryPass2(bundle, attempt)) {
            bundle = reExplore.apply(attempt);
            attempt++;
        }
        return attempt;
    }

    @Test
    @DisplayName("REQ-009: 3번째 유효 시드에서만 SUCCESS — 첫 FAILURE에 안 멈추고 재시도, SUCCESS 도달, 시도 ≤ budget+1")
    void retriesUntilSuccessWithinBudget() {
        List<List<ExploredPath>> seen = new ArrayList<>();
        // attempt 0,1 → FAILURE(엔벨로프-200/404), attempt 2 → 진짜 SUCCESS.
        int attempts = simulate(attempt -> {
            List<ExploredPath> b = attempt >= 2
                    ? pathsWith(Outcome.Kind.SUCCESS)
                    : pathsWith(Outcome.Kind.FAILURE);
            seen.add(b);
            return b;
        });

        assertThat(attempts).isLessThanOrEqualTo(EndpointExplorationRunner.RCB_RETRY_BUDGET + 1);
        assertThat(attempts).isEqualTo(3);   // 0,1 FAILURE + 2 SUCCESS
        assertThat(seen.get(seen.size() - 1).stream()
                .anyMatch(p -> p.outcome() == Outcome.Kind.SUCCESS)).isTrue();
    }

    @Test
    @DisplayName("REQ-009: 첫 pass-2가 이미 SUCCESS면 추가 시도 0(behavior-equivalent)")
    void noExtraAttemptsWhenFirstPass2Succeeds() {
        int attempts = simulate(attempt -> pathsWith(Outcome.Kind.SUCCESS));
        assertThat(attempts).isEqualTo(1);   // 최초 시도만, 재시도 없음
    }

    @Test
    @DisplayName("REQ-009: 예산 소진까지 계속 FAILURE면 budget+1회에서 멈춤(무한 루프 아님, best-effort 수용)")
    void stopsAtBudgetWhenAlwaysFailure() {
        int attempts = simulate(attempt -> pathsWith(Outcome.Kind.FAILURE));
        assertThat(attempts).isEqualTo(EndpointExplorationRunner.RCB_RETRY_BUDGET + 1);
    }

    @Test
    @DisplayName("REQ-009: shouldRetryPass2 술어 — SUCCESS 있으면 false, 전부 FAILURE면 예산 내 true")
    void predicateContract() {
        // SUCCESS 한 개라도 있으면 중단.
        assertThat(EndpointExplorationRunner.shouldRetryPass2(pathsWith(Outcome.Kind.SUCCESS), 1)).isFalse();
        // 전부 FAILURE면 예산 내에서 재시도.
        assertThat(EndpointExplorationRunner.shouldRetryPass2(pathsWith(Outcome.Kind.FAILURE), 1)).isTrue();
        assertThat(EndpointExplorationRunner.shouldRetryPass2(pathsWith(Outcome.Kind.FAILURE),
                EndpointExplorationRunner.RCB_RETRY_BUDGET)).isTrue();
        // 예산 소진 시 false.
        assertThat(EndpointExplorationRunner.shouldRetryPass2(pathsWith(Outcome.Kind.FAILURE),
                EndpointExplorationRunner.RCB_RETRY_BUDGET + 1)).isFalse();
    }
}
