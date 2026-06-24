package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import org.slf4j.*;
import java.util.*; import java.util.function.*;
public final class EgressCollector {
    private static final Logger log=LoggerFactory.getLogger(EgressCollector.class);
    private static final long POLL=50;
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
