package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.builder.staticanalysis.domain.Parameter;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the happy + boundary {@link NamedSampleInput}s for one endpoint.
 *
 * <p>Algorithm (deterministic):
 * <ol>
 *   <li>Categorize parameters by annotation simple-name into pathParams /
 *       queryParams / headers / body / ignored.</li>
 *   <li>Emit the happy input first.</li>
 *   <li>For each path/query parameter in declaration order, emit one variant per
 *       {@link BoundaryValueGenerator#variants}. Only the varying parameter
 *       changes; the others keep their happy values.</li>
 * </ol>
 *
 * <p>Header parameters and body fields do not produce variants in v1 (see spec §3.3).
 */
public final class SampleInputGenerator {

    /** Spring-binding sentinel parameter types we silently drop. */
    private static final Set<String> IGNORED_TYPES = Set.of(
            "Model", "ModelMap", "BindingResult",
            "HttpServletRequest", "HttpServletResponse",
            "HttpSession", "Authentication", "Principal");

    private enum Source { PATH, QUERY, HEADER, BODY, IGNORED }

    private SampleInputGenerator() {}

    public static List<NamedSampleInput> generate(
            Endpoint endpoint,
            MethodAnalysis methodAnalysis,
            BoundaryValueConfig cfg,
            ManualReviewSink sink) {

        List<Categorized> params = categorize(methodAnalysis.parameters(),
                endpoint.handlerClass(), endpoint.handlerMethod(), sink);

        List<NamedSampleInput> out = new ArrayList<>();
        out.add(happy(endpoint, params, cfg));

        for (Categorized p : params) {
            if (p.source != Source.PATH && p.source != Source.QUERY) continue;
            for (String variant : BoundaryValueGenerator.variants(p.param.type(), cfg)) {
                out.add(boundary(endpoint, params, p, variant, cfg));
            }
        }
        Set<String> phs = placeholdersOf(endpoint.path());
        if (phs.isEmpty()) return List.copyOf(out);
        return out.stream().map(ni -> fillUnboundPlaceholders(ni, phs)).toList();
    }

    private static List<Categorized> categorize(
            List<Parameter> parameters, String classFqn, String methodName,
            ManualReviewSink sink) {

        List<Categorized> out = new ArrayList<>(parameters.size());
        for (Parameter p : parameters) {
            Source src = sourceOf(p);
            if (src == Source.IGNORED) continue;
            if (src == Source.BODY) {
                // Body objects are not generated field-by-field in v1.
                sink.accept(new ManualReviewItem(
                        "complex_parameter_type",
                        "request body fields are not boundary-generated in v1",
                        classFqn + "#" + methodName + "(" + p.type() + " " + p.name() + ")"));
            } else if (!BoundaryValueGenerator.isNumeric(p.type())
                    && !BoundaryValueGenerator.isStringLike(p.type())) {
                sink.accept(new ManualReviewItem(
                        "complex_parameter_type",
                        "no boundary generator for type",
                        classFqn + "#" + methodName + "(" + p.type() + " " + p.name() + ")"));
            }
            out.add(new Categorized(p, src));
        }
        return out;
    }

    private static Source sourceOf(Parameter p) {
        if (p.annotations().contains("PathVariable"))   return Source.PATH;
        if (p.annotations().contains("RequestParam"))   return Source.QUERY;
        if (p.annotations().contains("RequestHeader"))  return Source.HEADER;
        if (p.annotations().contains("RequestBody"))    return Source.BODY;
        if (IGNORED_TYPES.contains(p.type()))           return Source.IGNORED;
        // Unannotated, non-sentinel → treat as query param (Spring default binding).
        return Source.QUERY;
    }

    private static NamedSampleInput happy(Endpoint endpoint, List<Categorized> params,
                                          BoundaryValueConfig cfg) {
        Map<String, String> pathParams = new LinkedHashMap<>();
        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Object body = null;
        for (Categorized c : params) {
            switch (c.source) {
                case PATH   -> pathParams.put(paramKey(c.param, "PathVariable"),
                                              BoundaryValueGenerator.happy(c.param.type(), cfg));
                case QUERY  -> queryParams.put(paramKey(c.param, "RequestParam"),
                                               BoundaryValueGenerator.happy(c.param.type(), cfg));
                case HEADER -> headers.put(paramKey(c.param, "RequestHeader"),
                                           BoundaryValueGenerator.happy(c.param.type(), cfg));
                case BODY   -> body = new LinkedHashMap<>();
                default -> { /* ignored */ }
            }
        }
        return new NamedSampleInput("happy", happyStatus(endpoint.method()),
                new SampleInput(headers, pathParams, queryParams, body));
    }

    private static NamedSampleInput boundary(Endpoint endpoint, List<Categorized> params,
                                             Categorized mutated, String variantValue,
                                             BoundaryValueConfig cfg) {
        Map<String, String> pathParams = new LinkedHashMap<>();
        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Object body = null;
        for (Categorized c : params) {
            String value = (c == mutated)
                    ? variantValue
                    : BoundaryValueGenerator.happy(c.param.type(), cfg);
            switch (c.source) {
                case PATH   -> pathParams.put(paramKey(c.param, "PathVariable"), value);
                case QUERY  -> queryParams.put(paramKey(c.param, "RequestParam"), value);
                case HEADER -> headers.put(paramKey(c.param, "RequestHeader"),
                                           BoundaryValueGenerator.happy(c.param.type(), cfg));
                case BODY   -> body = new LinkedHashMap<>();
                default -> { /* ignored */ }
            }
        }
        String slug = mutated.param.name() + "-" + slugifyValue(variantValue);
        int status = "".equals(variantValue) ? 400 : 404;
        return new NamedSampleInput(slug, status,
                new SampleInput(headers, pathParams, queryParams, body));
    }

    private static String slugifyValue(String v) {
        if (v.isEmpty()) return "empty";
        if (v.startsWith("-")) return "neg" + v.substring(1);
        return v;
    }

    private static int happyStatus(HttpMethod m) {
        return m == HttpMethod.POST ? 201 : 200;
    }

    private static String paramKey(Parameter p, String annotationName) {
        String v = p.annotationValues().get(annotationName);
        return v != null && !v.isEmpty() ? v : p.name();
    }

    // Identifier-only placeholder pattern — matches `{ownerId}` but NOT `{ "/vets" }`
    // (which can leak in from buggy annotation-value extraction upstream). Filling a
    // malformed placeholder would de-quarantine a path that scout-launcher then
    // chokes on as an illegal URI.
    private static final java.util.regex.Pattern URL_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private static Set<String> placeholdersOf(String urlTemplate) {
        Set<String> out = new LinkedHashSet<>();
        java.util.regex.Matcher m = URL_PLACEHOLDER.matcher(urlTemplate);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static NamedSampleInput fillUnboundPlaceholders(
            NamedSampleInput named, Set<String> placeholders) {
        Map<String, String> existing = named.input().pathParams();
        if (existing.keySet().containsAll(placeholders)) return named;
        LinkedHashMap<String, String> filled = new LinkedHashMap<>(existing);
        for (String p : placeholders) filled.putIfAbsent(p, "1");
        return new NamedSampleInput(
                named.slug(), named.predictedStatus(),
                new SampleInput(named.input().headers(), filled,
                                named.input().queryParams(), named.input().body()));
    }

    private record Categorized(Parameter param, Source source) {}
}
