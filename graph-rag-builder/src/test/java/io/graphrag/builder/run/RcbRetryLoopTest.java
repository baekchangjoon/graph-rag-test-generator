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

    /** wire status를 명시한 단일 path 번들. (outcome, wire-status) 조합을 직접 흉내 낸다. */
    private static List<ExploredPath> path(Outcome.Kind kind, int wireStatus) {
        return List.of(new ExploredPath("p", "ep", obj(), wireStatus, obj(),
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(),
                List.of(), List.of(), java.util.Map.of(), kind, wireStatus, String.valueOf(wireStatus)));
    }

    /** SUCCESS path(wire 200). */
    private static List<ExploredPath> success() {
        return path(Outcome.Kind.SUCCESS, 200);
    }

    /** 엔벨로프-마스킹 FAILURE(outcome=FAILURE, wire 200) — RC-B 재시도 대상. */
    private static List<ExploredPath> envelopeFailure() {
        return path(Outcome.Kind.FAILURE, 200);
    }

    /** 진짜 non-2xx FAILURE(outcome=FAILURE, wire 404) — StatusOnly 경로, 재시도 비대상. */
    private static List<ExploredPath> genuineNon2xxFailure() {
        return path(Outcome.Kind.FAILURE, 404);
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
    @DisplayName("REQ-009: 3번째 유효 시드에서만 SUCCESS — 첫 엔벨로프-FAILURE에 안 멈추고 재시도, SUCCESS 도달, 시도 ≤ budget+1")
    void retriesUntilSuccessWithinBudget() {
        List<List<ExploredPath>> seen = new ArrayList<>();
        // attempt 0,1 → 엔벨로프-FAILURE(wire 200), attempt 2 → 진짜 SUCCESS.
        int attempts = simulate(attempt -> {
            List<ExploredPath> b = attempt >= 2 ? success() : envelopeFailure();
            seen.add(b);
            return b;
        });

        assertThat(attempts).isLessThanOrEqualTo(EndpointExplorationRunner.RCB_RETRY_BUDGET + 1);
        assertThat(attempts).isEqualTo(3);   // 0,1 엔벨로프-FAILURE + 2 SUCCESS
        assertThat(seen.get(seen.size() - 1).stream()
                .anyMatch(p -> p.outcome() == Outcome.Kind.SUCCESS)).isTrue();
    }

    @Test
    @DisplayName("REQ-009: 첫 pass-2가 이미 SUCCESS면 추가 시도 0(behavior-equivalent)")
    void noExtraAttemptsWhenFirstPass2Succeeds() {
        int attempts = simulate(attempt -> success());
        assertThat(attempts).isEqualTo(1);   // 최초 시도만, 재시도 없음
    }

    @Test
    @DisplayName("REQ-009: 예산 소진까지 계속 엔벨로프-FAILURE면 budget+1회에서 멈춤(무한 루프 아님, best-effort 수용)")
    void stopsAtBudgetWhenAlwaysFailure() {
        int attempts = simulate(attempt -> envelopeFailure());
        assertThat(attempts).isEqualTo(EndpointExplorationRunner.RCB_RETRY_BUDGET + 1);
    }

    @Test
    @DisplayName("AC4: 진짜 non-2xx FAILURE(StatusOnly, wire 404)는 추가 재시도 0 — pass-2 정확히 1회(비-엔벨로프 SUT 무영향)")
    void noExtraRetriesForGenuineNon2xxFailure() {
        int attempts = simulate(attempt -> genuineNon2xxFailure());
        assertThat(attempts).isEqualTo(1);   // 최초 pass-2 1회만, 추가 재시도 없음(구버전 동작)
    }

    @Test
    @DisplayName("REQ-009/AC4: shouldRetryPass2 술어 — SUCCESS면 false, 엔벨로프-FAILURE(wire 2xx)만 예산 내 true, 진짜 non-2xx FAILURE는 false")
    void predicateContract() {
        // SUCCESS 한 개라도 있으면 중단.
        assertThat(EndpointExplorationRunner.shouldRetryPass2(success(), 1)).isFalse();
        // 엔벨로프-FAILURE(outcome=FAILURE && wire 2xx)면 예산 내에서 재시도.
        assertThat(EndpointExplorationRunner.shouldRetryPass2(envelopeFailure(), 1)).isTrue();
        assertThat(EndpointExplorationRunner.shouldRetryPass2(envelopeFailure(),
                EndpointExplorationRunner.RCB_RETRY_BUDGET)).isTrue();
        // 예산 소진 시 false.
        assertThat(EndpointExplorationRunner.shouldRetryPass2(envelopeFailure(),
                EndpointExplorationRunner.RCB_RETRY_BUDGET + 1)).isFalse();
        // 진짜 non-2xx FAILURE(StatusOnly)는 엔벨로프가 아니므로 재시도하지 않음(AC4).
        assertThat(EndpointExplorationRunner.shouldRetryPass2(genuineNon2xxFailure(), 1)).isFalse();
    }
}
