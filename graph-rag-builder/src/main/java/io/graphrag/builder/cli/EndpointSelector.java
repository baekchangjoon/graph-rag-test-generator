package io.graphrag.builder.cli;

import io.graphrag.model.Endpoint;
import io.graphrag.model.KafkaConsumer;
import io.graphrag.model.WsEndpoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** --endpoint 스펙(id 또는 "METHOD /path")을 탐색 단위 id로 해석. 미매칭 시 후보와 함께 실패. */
public final class EndpointSelector {

    private EndpointSelector() {}

    public static Set<String> resolve(List<String> specs, List<Endpoint> endpoints,
                                      List<WsEndpoint> wsEndpoints, List<KafkaConsumer> kafkaConsumers) {
        Set<String> ids = new LinkedHashSet<>();
        endpoints.forEach(e -> ids.add(e.id()));
        wsEndpoints.forEach(w -> ids.add(w.id()));
        kafkaConsumers.forEach(k -> ids.add(k.id()));

        Set<String> resolved = new LinkedHashSet<>();
        for (String raw : specs) {
            String spec = raw.strip();
            if (spec.isEmpty()) { continue; }
            if (ids.contains(spec)) { resolved.add(spec); continue; }
            String byMethodPath = matchMethodPath(spec, endpoints);
            if (byMethodPath != null) { resolved.add(byMethodPath); continue; }
            if (GlobMatcher.hasGlobMeta(spec)) {
                List<String> globHits = matchGlob(spec, endpoints, wsEndpoints, kafkaConsumers);
                if (!globHits.isEmpty()) { resolved.addAll(globHits); continue; }
            }
            throw new IllegalArgumentException(
                    "no explorable unit matches --endpoint '" + spec + "'. candidates: "
                            + candidates(endpoints, wsEndpoints, kafkaConsumers));
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("--endpoint had no resolvable (non-blank) spec");
        }
        return resolved;
    }

    /** "METHOD /path" → 일치하는 HTTP endpoint id (대소문자 무시 method). 없으면 null. */
    private static String matchMethodPath(String spec, List<Endpoint> endpoints) {
        int sp = spec.indexOf(' ');
        if (sp <= 0) { return null; }
        String method = spec.substring(0, sp).strip();
        String path = spec.substring(sp + 1).strip();
        for (Endpoint e : endpoints) {
            if (e.httpMethod().equalsIgnoreCase(method) && e.path().equals(path)) {
                return e.id();
            }
        }
        return null;
    }

    /** glob 셀렉터 → id 또는 "METHOD /path" 매칭 단위 id들(순서 보존). */
    private static List<String> matchGlob(String spec, List<Endpoint> endpoints,
            List<WsEndpoint> wsEndpoints, List<KafkaConsumer> kafkaConsumers) {
        List<String> hits = new ArrayList<>();
        // method 토큰만 대문자화(httpMethod는 EndpointIndexer가 대문자로 저장). path 는 case 보존
        // — spec 전체를 toUpperCase 하면 "/API/ORDERS"가 되어 소문자 path와 영구 미스(critical).
        String specMethodUpper = upperFirstToken(spec);
        for (Endpoint e : endpoints) {
            String methodPath = e.httpMethod().toUpperCase() + " " + e.path();
            if (GlobMatcher.matches(spec, e.id())
                    || GlobMatcher.matches(specMethodUpper, methodPath)) {
                hits.add(e.id());
            }
        }
        for (WsEndpoint w : wsEndpoints) {
            if (GlobMatcher.matches(spec, w.id())) { hits.add(w.id()); }
        }
        for (KafkaConsumer k : kafkaConsumers) {
            if (GlobMatcher.matches(spec, k.id())) { hits.add(k.id()); }
        }
        return hits;
    }

    /** spec 의 첫 공백 이전(=HTTP method 토큰)만 대문자화. 공백 없으면(=id glob) 원본 그대로. */
    private static String upperFirstToken(String spec) {
        int sp = spec.indexOf(' ');
        if (sp <= 0) { return spec; }
        return spec.substring(0, sp).toUpperCase() + spec.substring(sp);
    }

    private static String candidates(List<Endpoint> endpoints, List<WsEndpoint> wsEndpoints,
                                     List<KafkaConsumer> kafkaConsumers) {
        List<String> lines = new ArrayList<>();
        endpoints.forEach(e -> lines.add(e.id() + " (" + e.httpMethod() + " " + e.path() + ")"));
        wsEndpoints.forEach(w -> lines.add(w.id() + " (ws)"));
        kafkaConsumers.forEach(k -> lines.add(k.id() + " (kafka)"));
        return String.join(", ", lines);
    }
}
