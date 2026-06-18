package sample.ledger;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 폴백 수신측: 메시지 B3 헤더 → Brave 컨텍스트 복원(128-bit 보존) → 컨슈머 스레드 MDC에 traceId 반영. */
@Component
@ConditionalOnProperty(name = "eventuate.b3.fallback", havingValue = "true")
public class B3MessageInterceptor implements MessageInterceptor {
    private final Tracing tracing;
    private final ThreadLocal<Tracer.SpanInScope> scope = new ThreadLocal<>();
    public B3MessageInterceptor(Tracing tracing) { this.tracing = tracing; }

    @Override public void preHandle(String subscriberId, Message message) {
        String traceId = message.getHeader("X-B3-TraceId").orElse(null);
        String spanId = message.getHeader("X-B3-SpanId").orElse(null);
        if (traceId == null || spanId == null) return;
        try {
            // 128-bit traceId 보존(리뷰 GPT I7): 32-hex면 상위 64-bit를 traceIdHigh로.
            String hex = traceId.length() == 32 ? traceId : ("0000000000000000" + traceId);
            long high = Long.parseUnsignedLong(hex.substring(0, 16), 16);
            long low = Long.parseUnsignedLong(hex.substring(16), 16);
            brave.propagation.TraceContext ctx = brave.propagation.TraceContext.newBuilder()
                    .traceIdHigh(high).traceId(low)
                    .spanId(Long.parseUnsignedLong(spanId, 16))
                    .sampled(true).build();
            Span span = tracing.tracer().toSpan(ctx);
            scope.set(tracing.tracer().withSpanInScope(span));
        } catch (NumberFormatException ignored) {
            /* malformed B3 header: skip trace restore */
        }
    }

    @Override public void postHandle(String subscriberId, Message message, Throwable t) {
        Tracer.SpanInScope s = scope.get();
        if (s != null) { s.close(); scope.remove(); }
    }
}
