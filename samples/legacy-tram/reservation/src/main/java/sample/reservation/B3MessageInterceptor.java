package sample.reservation;

import brave.Tracer;
import brave.Tracing;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 폴백: 발행 메시지에 현재 Brave span의 B3를 헤더로 복사(수신측은 ledger의 동명 인터셉터가 복원).
 */
@Component
@ConditionalOnProperty(name = "eventuate.b3.fallback", havingValue = "true")
public class B3MessageInterceptor implements MessageInterceptor {
    private final Tracing tracing;

    public B3MessageInterceptor(Tracing tracing) { this.tracing = tracing; }

    @Override
    public void preSend(Message message) {
        Tracer tracer = tracing.tracer();
        brave.Span span = tracer.currentSpan();
        if (span != null) {
            String traceId = span.context().traceIdString();
            String spanId = span.context().spanIdString();
            message.setHeader("X-B3-TraceId", traceId);
            message.setHeader("X-B3-SpanId", spanId);
            message.setHeader("X-B3-Sampled", "1");
        }
    }
}
