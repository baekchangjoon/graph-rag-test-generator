package io.graphrag.builder.run;

import io.graphrag.model.KafkaConsumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage: Kafka consumer 반대-arm 변종 payload/key 합성 (producer/SUT 불요 단위). */
class KafkaCaptureRunnerVariantTest {

    private static final KafkaConsumer CONSUMER =
            new KafkaConsumer("kafka-order-events", "order.events", "g", "x.C", "on", "x.E");

    @Test
    void missingFieldPayload_isEmptyObject() {
        // 빈 payload → 역직렬화 시 전 필드 null → required-필드 null-guard early-return arm
        assertThat(KafkaCaptureRunner.missingFieldPayload().isEmpty()).isTrue();
    }

    @Test
    void variantKey_deterministicAndDistinctPerKind() {
        assertThat(KafkaCaptureRunner.variantKey(CONSUMER, "missing"))
                .isEqualTo("variant-missing-kafka-order-events");
        assertThat(KafkaCaptureRunner.variantKey(CONSUMER, "dup"))
                .isNotEqualTo(KafkaCaptureRunner.variantKey(CONSUMER, "missing"));
    }
}
