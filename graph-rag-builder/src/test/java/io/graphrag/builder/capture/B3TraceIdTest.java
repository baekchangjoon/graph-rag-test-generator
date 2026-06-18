package io.graphrag.builder.capture;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class B3TraceIdTest {

    @Test
    void headers_areB3MultiHeaderFormat() {
        B3TraceId.Ids ids = new B3TraceId("run-1", "nonce-1").next();
        assertThat(ids.traceId()).matches("[0-9a-f]{32}");
        assertThat(ids.spanId()).matches("[0-9a-f]{16}");
        Map<String, String> h = ids.headers();
        assertThat(h).containsEntry("X-B3-TraceId", ids.traceId());
        assertThat(h).containsEntry("X-B3-SpanId", ids.spanId());
        assertThat(h).containsEntry("X-B3-Sampled", "1");
        assertThat(h).containsEntry("b3", ids.traceId() + "-" + ids.spanId() + "-1");
    }

    @Test
    void deterministic_sameRunAndNonceSameSequence() {
        assertThat(new B3TraceId("run-1", "n").next().traceId())
                .isEqualTo(new B3TraceId("run-1", "n").next().traceId());
    }

    @Test
    void nonce_disambiguatesSameRunId() {
        // 동일 commit(runId) 동시 실행이라도 nonce가 다르면 trace 시퀀스가 충돌하지 않는다(R5)
        assertThat(new B3TraceId("run-1", "nonceA").next().traceId())
                .isNotEqualTo(new B3TraceId("run-1", "nonceB").next().traceId());
    }

    @Test
    void unique_acrossRequests() {
        B3TraceId b3 = new B3TraceId("run-1", "n");
        assertThat(b3.next().traceId()).isNotEqualTo(b3.next().traceId());
    }

    @Test
    void nullRunIdOrNonce_failsFast() {   // 리뷰 반영: null이 "null"로 묵음 처리되면 nonce 격리 무력화
        assertThatThrownBy(() -> new B3TraceId(null, "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new B3TraceId("run-1", null)).isInstanceOf(NullPointerException.class);
    }
}
