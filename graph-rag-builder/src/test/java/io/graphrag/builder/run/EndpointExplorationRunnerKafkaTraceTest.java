package io.graphrag.builder.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Kafka 캡처를 안 하는 attach 탐색 회귀: kafkaByTrace가 immutable Map.of()이고 candidate의
 * kafkaTraceId가 null일 때, buildPaths의 레코드 lookup이 NPE를 던지지 않아야 한다.
 */
class EndpointExplorationRunnerKafkaTraceTest {

    @Test
    void nullTraceIdAgainstImmutableEmptyMapReturnsEmptyWithoutNpe() {
        Map<String, List<KafkaCaptureReceiver.CapturedRecord>> noKafka = Map.of();
        assertThatCode(() -> EndpointExplorationRunner.kafkaRecordsForTrace(noKafka, null))
                .doesNotThrowAnyException();
        assertThat(EndpointExplorationRunner.kafkaRecordsForTrace(noKafka, null)).isEmpty();
    }

    @Test
    void unknownTraceIdReturnsEmpty() {
        Map<String, List<KafkaCaptureReceiver.CapturedRecord>> noKafka = Map.of();
        assertThat(EndpointExplorationRunner.kafkaRecordsForTrace(noKafka, "absent")).isEmpty();
    }
}
