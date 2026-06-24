package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-P009: 병렬 워커가 Kafka emit을 서로 소실시키지 않는지 검증(capturedEventEmits par4=0 회귀 방지).
 *
 * <p>근본원인: {@code drainAllByTraceId}가 큐 전체를 clear → 동시 호출 시 먼저 lock을 잡은 워커가
 * 다른 워커 레코드까지 drain·clear해 소실. 수정: {@code drainByTraceIds}는 자기 traceId 레코드만
 * 제거하고 나머지는 큐에 남긴다. Kafka 불요(순수 단위, offerForTest 주입).
 */
class KafkaCaptureReceiverParallelDrainTest {

    private static KafkaCaptureReceiver.CapturedRecord recFor(String traceId, String key) {
        String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";
        return new KafkaCaptureReceiver.CapturedRecord(
                "order.events", key, NullNode.getInstance(),
                Map.of("traceparent", traceparent));
    }

    @Test
    void drainByTraceIds_takesOnlyOwnTrace_leavesOthers() {
        KafkaCaptureReceiver r = new KafkaCaptureReceiver("localhost:0");
        String a = "a".repeat(32), b = "b".repeat(32), c = "c".repeat(32);
        r.offerForTest(recFor(a, "a1"));
        r.offerForTest(recFor(a, "a2"));
        r.offerForTest(recFor(b, "b1"));
        r.offerForTest(recFor(c, "c1"));

        // 워커1: a만 가져간다 → b,c는 큐에 남아야 한다.
        Map<String, List<KafkaCaptureReceiver.CapturedRecord>> got = r.drainByTraceIds(Set.of(a), 0);
        assertThat(got.keySet()).containsExactly(a);
        assertThat(got.get(a)).hasSize(2);

        // 워커2: b만 가져간다 → c는 여전히 남는다(전역 clear였다면 b가 이미 소실됐을 것).
        Map<String, List<KafkaCaptureReceiver.CapturedRecord>> got2 = r.drainByTraceIds(Set.of(b), 0);
        assertThat(got2.get(b)).as("drainAllByTraceId였다면 워커1이 b까지 clear해 비어있었을 것").hasSize(1);

        // c는 아직 큐에 — 그 owner가 가져갈 수 있다.
        Map<String, List<KafkaCaptureReceiver.CapturedRecord>> got3 = r.drainByTraceIds(Set.of(c), 0);
        assertThat(got3.get(c)).hasSize(1);
    }

    @Test
    void drainByTraceIds_concurrentWorkers_noCrossLoss() throws Exception {
        KafkaCaptureReceiver r = new KafkaCaptureReceiver("localhost:0");
        String a = "a".repeat(32), b = "b".repeat(32);
        r.offerForTest(recFor(a, "a1"));
        r.offerForTest(recFor(b, "b1"));

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Map<String, List<KafkaCaptureReceiver.CapturedRecord>>> ra = new AtomicReference<>();
        AtomicReference<Map<String, List<KafkaCaptureReceiver.CapturedRecord>>> rb = new AtomicReference<>();
        Thread ta = new Thread(() -> { await(start); ra.set(r.drainByTraceIds(Set.of(a), 0)); });
        Thread tb = new Thread(() -> { await(start); rb.set(r.drainByTraceIds(Set.of(b), 0)); });
        ta.start(); tb.start();
        start.countDown();
        ta.join(); tb.join();

        assertThat(ra.get().get(a)).as("워커A는 자기 emit 보존").hasSize(1);
        assertThat(rb.get().get(b)).as("워커B는 자기 emit 보존(교차 소실 없음)").hasSize(1);
    }

    @Test
    void drainByTraceIds_emptySet_returnsEmpty() {
        KafkaCaptureReceiver r = new KafkaCaptureReceiver("localhost:0");
        r.offerForTest(recFor("d".repeat(32), "d1"));
        assertThat(r.drainByTraceIds(Set.of(), 0)).isEmpty();
        assertThat(r.drainByTraceIds(null, 0)).isEmpty();
    }

    private static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
