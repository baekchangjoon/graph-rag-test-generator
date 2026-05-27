package io.graphrag.builder.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.JsonMappers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Writes each {@link CaptureContext} registered in {@link CaptureContextRegistry} as a
 * 4-JSON archive subdirectory under the scout-launcher's configured output dir.
 *
 * <p>Layout: {@code <root>/<pathId>/captured_sql.json} (plus empty stubs for
 * endpoints/paths/http so {@code test-generator --archive} can load it).
 *
 * <p>Endpoint + path metadata is left blank — the scout-launcher only knows about HTTP
 * steps, not the SUT's handler classes. The {@code test-generator} CLI's
 * {@code --endpoint} flag selects which scout pathId to synthesize.
 */
final class ArchiveShutdownWriter {

    private static final ObjectMapper M = JsonMappers.standard();

    private ArchiveShutdownWriter() {}

    static void dump(String rootDir) {
        try {
            Path root = Paths.get(rootDir);
            Files.createDirectories(root);
            Map<String, CaptureContext> snapshot = CaptureContextRegistry.snapshot();
            if (snapshot.isEmpty()) {
                System.err.println("[graphrag] no CaptureContext registered — nothing to dump to " + root);
                return;
            }
            for (var entry : snapshot.entrySet()) {
                writePathArchive(root.resolve(entry.getKey()), entry.getKey(), entry.getValue());
            }
            System.err.println("[graphrag] dumped " + snapshot.size() + " archive(s) to " + root.toAbsolutePath());
        } catch (Throwable t) {
            // Shutdown hooks must not throw.
            System.err.println("[graphrag] archive dump failed: " + t);
        }
    }

    private static void writePathArchive(Path dir, String pathId, CaptureContext ctx) throws IOException {
        Files.createDirectories(dir);
        List<CapturedSql> sqls = ctx.capturedSql();
        List<CapturedHttpCall> https = ctx.capturedHttpCalls();
        M.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("captured_sql.json").toFile(), sqls);
        M.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("captured_http.json").toFile(), https);
        // endpoints + paths are stubs — scout-launcher fills semantics out-of-band via the YAML config
        // (the test-generator CLI is invoked separately with the proper --endpoint + --package).
        M.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("endpoints.json").toFile(), List.of());
        M.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("paths.json").toFile(), List.of());
        System.err.println("[graphrag]   path=" + pathId + "  sqls=" + sqls.size() + "  https=" + https.size());
    }
}
