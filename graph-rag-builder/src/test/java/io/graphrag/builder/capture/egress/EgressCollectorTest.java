package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import org.junit.jupiter.api.*;
import java.util.*; import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
class EgressCollectorTest {
    private static SpanRecord client(String t,String p){ return new SpanRecord(t,"b".repeat(16),"c".repeat(16),"post","CLIENT",1L,Map.of("http.method","GET","http.path",p),List.of()); }
    @Test @DisplayName("REQ-004: collects only own trace")
    void ownTrace(){ String mine="a".repeat(32),other="f".repeat(32);
        Map<String,List<SpanRecord>> store=Map.of(mine,List.of(client(mine,"/a")),other,List.of(client(other,"/b")));
        var c=new EgressCollector(store::get,(t,q)->true,0,0);
        assertThat(c.collect(mine)).extracting(EgressCall::path).containsExactly("/a"); }
    @Test @DisplayName("REQ-007: awaits late-arriving span until quiescent")
    void awaitsLate() throws Exception {
        String t="a".repeat(32);
        Map<String,List<SpanRecord>> store=new ConcurrentHashMap<>();
        long[] arrived={0};
        java.util.function.BiPredicate<String,Long> quiescent=(tr,q)-> arrived[0]!=0 && (System.nanoTime()-arrived[0])>=q*1_000_000L;
        var c=new EgressCollector(store::get,quiescent,50,3000);
        new Thread(()->{ try{Thread.sleep(200);}catch(InterruptedException ignored){}
            store.put(t,List.of(client(t,"/late"))); arrived[0]=System.nanoTime(); }).start();
        assertThat(c.collect(t)).extracting(EgressCall::path).containsExactly("/late"); }
}
