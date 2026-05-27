package io.graphrag.scout.orchestrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.JsonMappers;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Persists per-step scout metadata into the same per-path archive subdirectories the SUT-side
 * bridge wrote {@code captured_sql.json} into, so {@code test-generator --archive --endpoint
 * <METHOD>:<path>} can find both halves of the picture (HTTP intent + SQL effects).
 *
 * <p>Per scout step, this writes three files under {@code <archive>/<pathId>/}:
 * <ul>
 *   <li>{@code endpoints.json} — single-element {@link Endpoint} list with {@code id}
 *       formatted as {@code METHOD:path}</li>
 *   <li>{@code paths.json} — single-element {@link ExploredPath} list whose {@code sampleInput}
 *       carries the scout's request (headers/body) and whose {@code exitStatus} +
 *       {@code exitResponseShape} carry the observed response</li>
 *   <li>{@code captured_http.json} — left as an empty array; in this scenario the SUT is the
 *       target, not the caller, so there are no outbound HTTP calls to record. (For SUTs that
 *       fan out to other services, a separate listener — out of scope for V1 — would populate
 *       this.)</li>
 * </ul>
 *
 * <p>This writer always runs AFTER the SUT shutdown hook has flushed
 * {@code captured_sql.json} (see {@link PipelineRunner}), so it safely overwrites the empty
 * stubs the bridge wrote for the three metadata files.
 */
public final class ScoutMetadataWriter {

    private static final ObjectMapper M = JsonMappers.standard();

    private final Path archiveDir;
    private final String project;

    public ScoutMetadataWriter(Path archiveDir, String project) {
        this.archiveDir = archiveDir;
        this.project = (project == null || project.isBlank()) ? "scout" : project;
    }

    public void write(List<ScoutResult> results) throws IOException {
        for (ScoutResult r : results) {
            writeOne(r);
        }
        System.out.println("[scout] wrote endpoints/paths metadata for " + results.size()
                + " step(s) under " + archiveDir.toAbsolutePath());
    }

    void writeOne(ScoutResult r) throws IOException {
        Path dir = archiveDir.resolve(r.step().pathId());
        // Only create if missing. If the bridge already wrote captured_sql.json into this
        // dir we want to land alongside it, not in a new location.
        Files.createDirectories(dir);

        Endpoint endpoint = new Endpoint(
                endpointId(r),
                parseMethod(r.step().method()),
                r.step().path(),
                project,
                "unknown",        // scout-launcher doesn't have handler symbol info
                "unknown",
                false,            // auth handled out-of-band via configured headers
                List.of());

        SampleInput sampleInput = new SampleInput(
                r.requestHeaders(),
                Map.of(),         // no separate path-param map; the literal path stays as-is
                Map.of(),         // no separate query-param map
                r.requestBody());

        ExploredPath exploredPath = new ExploredPath(
                "scout-" + r.step().pathId(),
                endpointId(r),
                PathExplorerKind.MANUAL,
                sampleInput,
                null,             // pathConstraint — only colcolic engines populate this
                List.of(),
                r.responseStatus(),
                parseJsonOrNull(r.responseBody()),
                "scout",          // coverageSignature — placeholder; scout doesn't compute coverage
                "scout");         // codeVersion — placeholder; scout doesn't know the SHA

        M.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("endpoints.json").toFile(), List.of(endpoint));
        M.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("paths.json").toFile(), List.of(exploredPath));
        M.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("captured_http.json").toFile(), List.of());
    }

    static String endpointId(ScoutResult r) {
        return r.step().method().toUpperCase() + ":" + r.step().path();
    }

    private static HttpMethod parseMethod(String m) {
        try { return HttpMethod.valueOf(m.toUpperCase()); }
        catch (IllegalArgumentException ex) { return HttpMethod.GET; }
    }

    /**
     * Best-effort JSON parse — if the body is JSON we hand the parsed object to
     * test-generator so it can match field-by-field; otherwise we keep the raw string so the
     * synthesized test can still do an equals-comparison.
     */
    private static Object parseJsonOrNull(String body) {
        if (body == null || body.isEmpty()) return null;
        try { return M.readValue(body, Object.class); }
        catch (Exception ex) { return body; }
    }
}
