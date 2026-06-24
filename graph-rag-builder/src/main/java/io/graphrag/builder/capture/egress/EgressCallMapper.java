package io.graphrag.builder.capture.egress;
import io.graphrag.model.CapturedHttpCall;
import java.util.*;
public final class EgressCallMapper {
    private EgressCallMapper() {}
    public static CapturedHttpCall toCapturedHttpCall(EgressCall e, String pathId, int seq){
        return new CapturedHttpCall("http-"+pathId+"-egress-"+seq, pathId, e.method(), e.path(),
            Map.of(), null, e.statusOrNull()==null?200:e.statusOrNull(), "",
            List.of(), false, CapturedHttpCall.Provenance.CAPTURED);
    }
    /** 한 요청(단일 trace) 내 (method,urlPath) dedup, existing(redirect) 우선. 교차-trace 아님. */
    public static List<CapturedHttpCall> mergeDedup(List<CapturedHttpCall> existing, List<CapturedHttpCall> egress){
        Set<String> seen=new HashSet<>(); List<CapturedHttpCall> out=new ArrayList<>();
        for (CapturedHttpCall c:existing){ seen.add(c.method()+" "+c.urlPath()); out.add(c); }
        for (CapturedHttpCall c:egress) if (seen.add(c.method()+" "+c.urlPath())) out.add(c);
        return out;
    }
}
