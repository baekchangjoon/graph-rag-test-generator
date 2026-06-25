package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import io.graphrag.builder.env.ExplorationEnvironment;
import org.slf4j.*;
import java.util.*; import java.util.function.*;
public final class EgressCollector {
    private static final Logger log=LoggerFactory.getLogger(EgressCollector.class);
    private static final long POLL=50;

    /** Brave AsyncReporter flush ~1s 기준 시작값. E2E에서 실측·조정 예정. */
    static final long ZIPKIN_QUIESCENCE_MILLIS = 1200;
    static final long ZIPKIN_AWAIT_MILLIS = 3000;

    /**
     * OTLP quiescence 창. SUT BSP는 {@code OTEL_BSP_SCHEDULE_DELAY=100ms}로 export하므로
     * (OtelAgent.otlpEnv), egress CLIENT span은 entry/DB span과 다른 배치(≥1 BSP 주기 뒤)로 도착할 수
     * 있다. quiescence가 한 배치 간격(100ms)보다 충분히 커야 늦게 오는 CLIENT span 직전에 조기 완료되지
     * 않는다(이전 150ms는 1.5×에 불과해 CI 부하 시 egress span을 간헐적으로 놓쳐 httpCalls=[] flaky 유발).
     * 5×BSP=500ms는 "한 배치도 더 안 옴"을 견고히 판정한다. await(8s)는 그대로 충분.
     */
    static final long OTLP_QUIESCENCE_MILLIS = 500;
    static final long OTLP_AWAIT_MILLIS = 8000;

    /**
     * 모드에 맞는 EgressCollector를 생성한다.
     * - otlpReceiver가 non-null이면 OTLP 기반 collector(quiescence=500ms, await=8000ms).
     * - zipkinReceiver가 non-null이면 Zipkin 기반 collector(quiescence=1200ms, await=3000ms).
     * - 둘 다 null이면 egress 비활성(null 반환).
     */
    public static EgressCollector forMode(ExplorationEnvironment env) {
        if (env.otlpReceiver() != null) {
            var r = env.otlpReceiver();
            return new EgressCollector(r::spans, r::isQuiescent, OTLP_QUIESCENCE_MILLIS, OTLP_AWAIT_MILLIS);
        }
        if (env.zipkinReceiver() != null) {
            var r = env.zipkinReceiver();
            return new EgressCollector(r::spans, r::isQuiescent, ZIPKIN_QUIESCENCE_MILLIS, ZIPKIN_AWAIT_MILLIS);
        }
        return null;
    }

    private final Function<String,List<SpanRecord>> spanSource;
    private final BiPredicate<String,Long> quiescent; private final long quiescenceMillis, awaitMillis;
    public EgressCollector(Function<String,List<SpanRecord>> s, BiPredicate<String,Long> q, long qm, long am){
        spanSource=s; quiescent=q; quiescenceMillis=qm; awaitMillis=am; }
    public List<EgressCall> collect(String traceId){
        if (traceId==null) return List.of();
        long deadline=System.nanoTime()+awaitMillis*1_000_000L;
        while (awaitMillis>0 && System.nanoTime()<deadline && !quiescent.test(traceId,quiescenceMillis)) sleep(POLL);
        if (awaitMillis>0 && !quiescent.test(traceId,quiescenceMillis))
            log.warn("egress collect: quiescence not reached for trace {} within {}ms", traceId, awaitMillis);
        List<SpanRecord> spans=spanSource.apply(traceId); if (spans==null) return List.of();
        List<EgressCall> out=new ArrayList<>();
        spans.stream().sorted(Comparator.comparingLong(SpanRecord::startUnixNano))
             .forEach(s->EgressNormalizer.fromSpan(s).ifPresent(out::add));
        return out;
    }
    private static void sleep(long ms){ try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();} }
}
