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

    private static String candidates(List<Endpoint> endpoints, List<WsEndpoint> wsEndpoints,
                                     List<KafkaConsumer> kafkaConsumers) {
        List<String> lines = new ArrayList<>();
        endpoints.forEach(e -> lines.add(e.id() + " (" + e.httpMethod() + " " + e.path() + ")"));
        wsEndpoints.forEach(w -> lines.add(w.id() + " (ws)"));
        kafkaConsumers.forEach(k -> lines.add(k.id() + " (kafka)"));
        return String.join(", ", lines);
    }
}
