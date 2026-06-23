package io.graphrag.builder.env;

import java.util.Map;
import java.util.Optional;

/** trace 미사용(none) 모드: trace-id 없음. 실행 직렬 전제에서 unmatched 전체를 현재 요청에 귀속. */
public final class NoTraceKey implements TraceKey {

    @Override
    public Optional<String> readTraceId(Map<String, String> outboundHeaders) {
        return Optional.empty();
    }
}
