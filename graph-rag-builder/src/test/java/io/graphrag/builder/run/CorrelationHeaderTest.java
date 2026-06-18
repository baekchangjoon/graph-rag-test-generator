package io.graphrag.builder.run;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationHeaderTest {

    @Test
    void stripsUserCorrelationHeaders_caseInsensitive_thenOverlaysScope() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("X-Custom", "keep");
        user.put("TraceParent", "00-usertrace-userspan-01");
        user.put("x-b3-traceid", "userb3");
        user.put("b3", "user-b3-1");

        Map<String, String> scope = Map.of(
                "X-B3-TraceId", "backendtrace",
                "X-B3-SpanId", "backendspan",
                "X-B3-Sampled", "1",
                "b3", "backendtrace-backendspan-1");

        Map<String, String> merged = EndpointExplorationRunner.applyCorrelationPriority(user, scope);

        assertThat(merged).containsEntry("X-Custom", "keep");                 // 비상관 헤더 유지
        assertThat(merged).containsEntry("X-B3-TraceId", "backendtrace");     // backend 우선
        assertThat(merged).containsEntry("b3", "backendtrace-backendspan-1");
        // 사용자 traceparent/대소문자 변형 b3는 제거됨(중복 전파 방지)
        assertThat(merged).doesNotContainKey("TraceParent");
        assertThat(merged).doesNotContainKey("traceparent");
        assertThat(merged.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("traceparent"))).isTrue();
    }

    @Test
    void noScopeHeaders_keepsUserNonCorrelationOnly() {
        Map<String, String> user = Map.of("Authorization", "Bearer x", "traceparent", "00-a-b-01");
        Map<String, String> merged = EndpointExplorationRunner.applyCorrelationPriority(user, Map.of());
        assertThat(merged).containsEntry("Authorization", "Bearer x");
        assertThat(merged.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("traceparent"))).isTrue();
    }
}
