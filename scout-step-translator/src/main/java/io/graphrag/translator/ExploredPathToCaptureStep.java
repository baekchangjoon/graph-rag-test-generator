package io.graphrag.translator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;
import io.graphrag.model.SampleInput;
import io.graphrag.scout.config.CaptureStep;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mechanical converter from the Stage 1 fact ({@link ExploredPath} + its
 * {@link Endpoint}) to the Stage 3 instruction ({@link CaptureStep}) that
 * {@code scout-launcher} executes.
 *
 * <p>What survives the conversion (and why each survives):
 * <ul>
 *   <li>{@code path-id} is copied verbatim — {@code ArchiveReader} joins
 *       {@code CapturedSql.path_id} back to this exact string. Renaming would
 *       silently break the join and turn the archive into orphan rows. (R2)</li>
 *   <li>{@code expected-status} = {@code ExploredPath.exit_status}. If the live
 *       SUT returns something else, {@code HttpScout} will emit a WARN; T3's
 *       {@code --strict} mode will quarantine such mismatches. (R3)</li>
 *   <li>HTTP {@code method} and template {@code path} are read from
 *       {@link Endpoint}, not {@link ExploredPath} — {@code ExploredPath}
 *       deliberately omits them since the endpoint owns the contract.</li>
 *   <li>{@code path-params} / {@code query-params} are substituted/appended via
 *       {@link PathTemplateExpander}.</li>
 *   <li>{@code body} is JSON-serialized for {@code Map}/{@code List}/{@code Number}
 *       /{@code Boolean}, passed through for {@code String}, and yields
 *       {@code null} for {@code null}.</li>
 * </ul>
 */
public final class ExploredPathToCaptureStep {

    private static final ObjectMapper M = JsonMappers.standard();

    private static final String HDR_CONTENT_TYPE = "Content-Type";

    private ExploredPathToCaptureStep() {}

    public static CaptureStep convert(ExploredPath path, Endpoint endpoint) {
        if (!endpoint.id().equals(path.endpointId())) {
            throw new IllegalArgumentException("endpoint_id mismatch: path expects '"
                    + path.endpointId() + "' but endpoint id is '" + endpoint.id() + "'");
        }
        SampleInput input = path.sampleInput();

        String expandedPath = PathTemplateExpander.expand(
                endpoint.path(), input.pathParams(), input.queryParams());

        BodyEncoding encoded = encodeBody(input);
        Map<String, String> headers = stripContentType(input.headers());
        String contentType = pickContentType(input.headers(), encoded);

        return new CaptureStep(
                path.id(),
                endpoint.method().name(),
                expandedPath,
                encoded.serialized,
                contentType,
                headers,
                path.exitStatus());
    }

    private static BodyEncoding encodeBody(SampleInput input) {
        Object raw = input.body();
        if (raw == null) return new BodyEncoding(null, null);
        if (raw instanceof String s) return new BodyEncoding(s, "text/plain");
        try {
            return new BodyEncoding(M.writeValueAsString(raw), "application/json");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "could not serialize body for path " + input, ex);
        }
    }

    /**
     * If the caller specified a Content-Type header, that wins — the user knows their
     * server. Otherwise fall back to the default the body shape implies.
     */
    private static String pickContentType(Map<String, String> headers, BodyEncoding encoded) {
        for (var entry : headers.entrySet()) {
            if (HDR_CONTENT_TYPE.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return encoded.defaultContentType;
    }

    /**
     * Hoist Content-Type into the dedicated {@code CaptureStep.contentType} field and
     * remove it from the headers map so HttpScout doesn't end up sending two copies.
     */
    private static Map<String, String> stripContentType(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>(in.size());
        for (var entry : in.entrySet()) {
            if (!HDR_CONTENT_TYPE.equalsIgnoreCase(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private record BodyEncoding(String serialized, String defaultContentType) {}
}
