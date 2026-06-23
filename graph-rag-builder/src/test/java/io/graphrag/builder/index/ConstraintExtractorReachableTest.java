package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-011: 핸들러 1-hop reachable 메서드 추출.
 * 픽스처: sample-src/io/graphrag/sample/delegation/ (컨트롤러→서비스 위임)
 */
class ConstraintExtractorReachableTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    private static final String HANDLER_CLASS =
            "io.graphrag.sample.delegation.ReservationController";
    private static final String HANDLER_METHOD = "listReservations";

    @Test
    void oneHopIncludesService() {
        Set<Map.Entry<String, String>> reachable = new ConstraintExtractor()
                .reachableMethods(SAMPLE_SRC, HANDLER_CLASS, HANDLER_METHOD);

        // 핸들러 본문에서 service.list(minNights) 를 1-hop 호출 →
        // declaringType = ReservationService (FQN 또는 simpleName으로 키가 들어옴)
        boolean containsServiceList = reachable.stream().anyMatch(e ->
                e.getKey().contains("ReservationService") && e.getValue().equals("list"));
        assertThat(containsServiceList)
                .as("reachable 집합에 (ReservationService, 'list') 가 포함되어야 한다")
                .isTrue();
    }

    @Test
    void handlerSelf() {
        Set<Map.Entry<String, String>> reachable = new ConstraintExtractor()
                .reachableMethods(SAMPLE_SRC, HANDLER_CLASS, HANDLER_METHOD);

        // 핸들러 자신 (handlerClass, handlerMethod) 도 집합에 포함되어야 한다
        boolean containsSelf = reachable.stream().anyMatch(e ->
                e.getKey().equals(HANDLER_CLASS) && e.getValue().equals(HANDLER_METHOD));
        assertThat(containsSelf)
                .as("reachable 집합에 핸들러 자신 (ReservationController, 'listReservations') 이 포함되어야 한다")
                .isTrue();
    }
}
