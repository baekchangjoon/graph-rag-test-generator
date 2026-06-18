package io.graphrag.builder.capture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * sleuth 모드용 B3 trace-id 발급기. TraceParent(결정적 32-hex traceId / 16-hex spanId 생성)에 위임하고
 * B3 멀티헤더 포맷만 책임진다. 시드는 runId + per-run nonce(R5): 동일 commit 동시 실행이 trace 시퀀스를
 * 재생→충돌→캡처 교차오염하지 않도록 nonce로 분리하되, nonce를 외부 주입해 테스트 결정성을 유지한다.
 */
public final class B3TraceId {

    private final TraceParent delegate;

    public B3TraceId(String runId, String nonce) {
        // null이 "null" 문자열로 묵음 처리되면 nonce 격리(R5)가 깨지므로 fail-fast.
        this.delegate = new TraceParent(
                Objects.requireNonNull(runId, "runId") + ":" + Objects.requireNonNull(nonce, "nonce"));
    }

    public Ids next() {
        TraceParent.Ids d = delegate.next();
        return new Ids(d.traceId(), d.spanId());
    }

    public record Ids(String traceId, String spanId) {
        public Map<String, String> headers() {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("X-B3-TraceId", traceId);
            h.put("X-B3-SpanId", spanId);
            h.put("X-B3-Sampled", "1");
            h.put("b3", traceId + "-" + spanId + "-1");
            return h;
        }
    }
}
