package io.graphrag.builder.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 메소드 denylist — 입력 합성에서 특정 (type, method) 경로를 회피한다.
 *
 * <p>환경변수 또는 시스템 프로퍼티 {@value #ENV}: 쉼표/세미콜론 구분
 * {@code FQN#methodName} 목록 (예: {@code com.example.PaymentService#charge}).
 * noClasspath simpleName({@code PaymentService#charge})도 {@link #matches}에서 FQN과 동치 매칭.
 */
public final class SynthesisMethodFilter {

    public static final String ENV = "GRB_SYNTH_EXCLUDE_METHODS";

    private static final Logger log = LoggerFactory.getLogger(SynthesisMethodFilter.class);

    private SynthesisMethodFilter() {}

    public static Set<Map.Entry<String, String>> fromEnvironment() {
        String raw = System.getenv(ENV);
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty(ENV);
        }
        return parse(raw == null ? "" : raw);
    }

    public static Set<Map.Entry<String, String>> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<Map.Entry<String, String>> out = new LinkedHashSet<>();
        for (String token : raw.split("[,;]+")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int sep = trimmed.indexOf('#');
            if (sep <= 0 || sep == trimmed.length() - 1) {
                log.warn("ignoring invalid {} entry (expected Type#method): {}", ENV, trimmed);
                continue;
            }
            String type = trimmed.substring(0, sep).trim();
            String method = trimmed.substring(sep + 1).trim();
            if (type.isEmpty() || method.isEmpty()) {
                log.warn("ignoring invalid {} entry (empty type or method): {}", ENV, trimmed);
                continue;
            }
            out.add(new AbstractMap.SimpleEntry<>(type, method));
        }
        return Set.copyOf(out);
    }

    /**
     * {@code refs}에 (classFqn, method)가 속하는지 판정. REQ-012 {@code isReachable}과 동일한
     * simpleName endsWith 폴백을 사용한다.
     */
    public static boolean matches(Set<Map.Entry<String, String>> refs, String classFqn, String method) {
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        if (refs.contains(new AbstractMap.SimpleEntry<>(classFqn, method))) {
            return true;
        }
        for (Map.Entry<String, String> entry : refs) {
            if (!entry.getValue().equals(method)) {
                continue;
            }
            String refType = entry.getKey();
            if (classFqn.endsWith("." + refType)) {
                return true;
            }
            if (refType.endsWith("." + classFqn)) {
                return true;
            }
        }
        return false;
    }

    /** 핸들러 reachable(1-hop) 집합이 denylist와 교차하면 true — base happy 합성 skip(C) 신호. */
    public static boolean reachableTouchesExcluded(Set<Map.Entry<String, String>> reachable,
                                                   Set<Map.Entry<String, String>> exclude) {
        if (exclude == null || exclude.isEmpty() || reachable == null || reachable.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> reachableEntry : reachable) {
            if (matches(exclude, reachableEntry.getKey(), reachableEntry.getValue())) {
                return true;
            }
        }
        return false;
    }
}
