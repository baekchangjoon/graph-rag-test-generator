package io.graphrag.builder.run;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-012: responsevar arm traceId 누적 헬퍼 단위 테스트.
 *
 * <p>collectArmTraceIds가 null을 걸러내고 중복을 제거하되 순서를 보존하는지 검증한다.
 */
class ResponseVariantTraceTest {

    @Test
    void accumulatesNonNullDistinctArmTraceIds() {
        List<String> acc = EndpointExplorationRunner.collectArmTraceIds(
                Arrays.asList("a1", null, "a2", "a1"));
        assertThat(acc).containsExactly("a1", "a2");
    }
}
