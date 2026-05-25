package io.graphrag.builder.exploration;

import io.graphrag.model.Endpoint;
import io.graphrag.model.SampleInput;

import java.util.List;

/**
 * 분기 탐색 엔진의 SPI.
 *
 * <p>역할: 주어진 endpoint에 대해 다양한 입력을 제안. 실 호출/캡처는 ExplorationHarness가 담당.
 *
 * <p>Phase 1 구현체:
 * <ul>
 *   <li>{@link ManualPathExplorer} — 사전 정의 입력 세트 (단순)
 *   <li>(Phase 1.2) CoverageGuidedFuzzer — JaCoCo 피드백 진화
 *   <li>(Phase 1.3) JDartBridge — 콘콜릭 실행
 * </ul>
 */
public interface PathExplorer {
    /** 어댑터 식별자 (e.g., "manual", "fuzzer", "jdart"). */
    String name();

    /**
     * 입력 제안.
     *
     * <p>같은 입력이라도 budget으로 제한되어 잘릴 수 있음. 구현체는 결정적이어야 함
     * (동일 endpoint + budget → 동일 입력 리스트).
     */
    List<SampleInput> proposeInputs(Endpoint endpoint, ExplorationBudget budget);
}
