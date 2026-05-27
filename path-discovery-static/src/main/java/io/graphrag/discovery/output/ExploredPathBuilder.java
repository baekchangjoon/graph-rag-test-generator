package io.graphrag.discovery.output;

import io.graphrag.discovery.DiscoveredHandler;
import io.graphrag.discovery.HandlerParam;
import io.graphrag.discovery.heuristic.BoundaryValueGenerator;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates one or more {@link ExploredPath}s per {@link DiscoveredHandler}:
 * <ul>
 *   <li>One <strong>happy path</strong> per handler. {@code exitStatus} guessed from
 *       the HTTP method (200 for GET/PUT/DELETE/PATCH, 201 for POST).</li>
 *   <li>Additional <strong>boundary-value variants</strong> for each numeric
 *       {@code @PathVariable}. The variant uses the boundary value for that one param
 *       and the happy value for the others.
 *       {@code exitStatus} guessed as 400 (Spring binding error) for "" and 404
 *       (entity not found) for {@code -1} / large numbers. The downstream
 *       scout-launcher strict-mode will quarantine wrong predictions.</li>
 * </ul>
 *
 * <p>{@code id} is a deterministic slug — {@code "static_{handlerMethod}_{variant}"} —
 * so the same source code always yields the same paths.json (R6 idempotency).
 */
public final class ExploredPathBuilder {

    private ExploredPathBuilder() {}

    public static List<ExploredPath> build(DiscoveredHandler handler, Endpoint endpoint,
                                           String codeVersion) {
        List<ExploredPath> out = new ArrayList<>();
        out.add(happy(handler, endpoint, codeVersion));
        for (HandlerParam p : handler.pathParams()) {
            if (!BoundaryValueGenerator.isNumeric(p.typeName())) continue;
            Set<String> values = BoundaryValueGenerator.generate(p);
            boolean first = true;
            for (String v : values) {
                if (first) { first = false; continue; }   // first = happy value, already emitted
                out.add(variant(handler, endpoint, p, v, codeVersion));
            }
        }
        return out;
    }

    private static ExploredPath happy(DiscoveredHandler h, Endpoint ep, String codeVersion) {
        Map<String, String> pathParams = new LinkedHashMap<>();
        for (HandlerParam p : h.pathParams()) {
            pathParams.put(p.name(), happyValueOf(p));
        }
        Map<String, String> queryParams = new LinkedHashMap<>();
        for (HandlerParam p : h.queryParams()) {
            queryParams.put(p.name(), happyValueOf(p));
        }
        return new ExploredPath(
                slug(h, "happy"),
                ep.id(),
                PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), pathParams, queryParams, h.hasRequestBody() ? Map.of() : null),
                null,
                List.of(h.handlerClass() + "." + h.handlerMethod() + ":happy"),
                happyStatus(h.method()),
                null,
                "static:" + ep.id() + ":happy",
                codeVersion);
    }

    private static ExploredPath variant(DiscoveredHandler h, Endpoint ep,
                                        HandlerParam mutated, String value,
                                        String codeVersion) {
        Map<String, String> pathParams = new LinkedHashMap<>();
        for (HandlerParam p : h.pathParams()) {
            pathParams.put(p.name(), p.name().equals(mutated.name()) ? value : happyValueOf(p));
        }
        Map<String, String> queryParams = new LinkedHashMap<>();
        for (HandlerParam p : h.queryParams()) {
            queryParams.put(p.name(), happyValueOf(p));
        }
        String tag = mutated.name() + "-" + slugifyValue(value);
        return new ExploredPath(
                slug(h, tag),
                ep.id(),
                PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), pathParams, queryParams, h.hasRequestBody() ? Map.of() : null),
                null,
                List.of(h.handlerClass() + "." + h.handlerMethod() + ":" + tag),
                predictedStatusForBoundary(value),
                null,
                "static:" + ep.id() + ":" + tag,
                codeVersion);
    }

    private static String happyValueOf(HandlerParam p) {
        return BoundaryValueGenerator.generate(p).iterator().next();
    }

    private static String slug(DiscoveredHandler h, String variant) {
        return "static_" + h.handlerMethod() + "_" + variant;
    }

    private static String slugifyValue(String v) {
        if (v.isEmpty()) return "empty";
        if (v.startsWith("-")) return "neg" + v.substring(1);
        return v;
    }

    private static int happyStatus(HttpMethod m) {
        return switch (m) {
            case POST -> 201;
            default -> 200;
        };
    }

    /** Guess what Spring will do given a numeric boundary input. Imperfect — see R3 / R5. */
    private static int predictedStatusForBoundary(String value) {
        if (value.isEmpty()) return 400;                     // missing required param
        // For now, all numeric boundaries we generate (-1, 0, MAX_INT) predict 404 — the
        // record-by-id is unlikely to exist. The strict-mode quarantine (T3) catches
        // wrong predictions; a future R5 improvement would refine this with per-handler
        // exception-flow analysis.
        return 404;
    }
}
