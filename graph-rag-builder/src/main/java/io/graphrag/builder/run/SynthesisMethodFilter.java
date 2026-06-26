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
 * <p>환경변수 또는 시스템 프로퍼티 {@value #ENV}: 쉼표/세미콜론 구분 항목.
 * <ul>
 *   <li>{@code FQN#methodName} — 단일 메소드 (예: {@code com.example.PaymentService#charge})</li>
 *   <li>{@code FQN.*} 또는 {@code FQN#*} — 타입의 모든 메소드 (예: {@code PaymentService.*})</li>
 * </ul>
 * noClasspath simpleName({@code PaymentService#charge})도 {@link #matches}에서 FQN과 동치 매칭.
 */
public final class SynthesisMethodFilter {

    public static final String ENV = "GRB_SYNTH_EXCLUDE_METHODS";

    /** Wildcard method token: deny all methods on the matched type. */
    static final String WILDCARD_METHOD = "*";

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
            Map.Entry<String, String> parsed = parseEntry(trimmed);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return Set.copyOf(out);
    }

    private static Map.Entry<String, String> parseEntry(String trimmed) {
        if (trimmed.endsWith(".*") && !trimmed.contains("#")) {
            String type = trimmed.substring(0, trimmed.length() - 2).trim();
            if (type.isEmpty()) {
                log.warn("ignoring invalid {} entry (empty type before .*): {}", ENV, trimmed);
                return null;
            }
            return new AbstractMap.SimpleEntry<>(type, WILDCARD_METHOD);
        }
        int sep = trimmed.indexOf('#');
        if (sep <= 0 || sep == trimmed.length() - 1) {
            log.warn("ignoring invalid {} entry (expected Type#method or Type.*): {}", ENV, trimmed);
            return null;
        }
        String type = trimmed.substring(0, sep).trim();
        String method = trimmed.substring(sep + 1).trim();
        if (type.isEmpty() || method.isEmpty()) {
            log.warn("ignoring invalid {} entry (empty type or method): {}", ENV, trimmed);
            return null;
        }
        return new AbstractMap.SimpleEntry<>(type, method);
    }

    /**
     * {@code refs}에 (classFqn, method)가 속하는지 판정. REQ-012 {@code isReachable}과 동일한
     * simpleName endsWith 폴백을 사용한다. method가 {@value #WILDCARD_METHOD}인 항목은 타입만 일치하면 매칭.
     */
    public static boolean matches(Set<Map.Entry<String, String>> refs, String classFqn, String method) {
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        if (refs.contains(new AbstractMap.SimpleEntry<>(classFqn, method))) {
            return true;
        }
        for (Map.Entry<String, String> entry : refs) {
            if (!typesMatch(entry.getKey(), classFqn)) {
                continue;
            }
            if (WILDCARD_METHOD.equals(entry.getValue()) || entry.getValue().equals(method)) {
                return true;
            }
        }
        return false;
    }

    static boolean typesMatch(String refType, String classFqn) {
        if (refType.equals(classFqn)) {
            return true;
        }
        if (classFqn.endsWith("." + refType)) {
            return true;
        }
        return refType.endsWith("." + classFqn);
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
