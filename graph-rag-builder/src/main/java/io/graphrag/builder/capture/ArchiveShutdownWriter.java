package io.graphrag.builder.capture;

import io.graphrag.model.Binding;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.SourceLocation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
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
 *
 * <p><b>No Jackson dependency.</b> Jackson on the SUT's boot classpath conflicts with
 * the SUT's own (potentially different-major) Jackson at runtime, so this writer emits
 * JSON manually. The schema matches what {@code shared-model/JsonMappers} (snake_case)
 * produces — so {@code test-generator --archive} round-trips it without changes.
 */
final class ArchiveShutdownWriter {

    private ArchiveShutdownWriter() {}

    static void dump(String rootDir) {
        try {
            Path root = Paths.get(rootDir);
            Files.createDirectories(root);
            // Always write diagnostic stats so a developer can tell whether the agent's advice
            // ever invoked the bridge, regardless of whether any pathId could be resolved.
            writeStats(root);
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
        write(dir.resolve("captured_sql.json"), sqlsJson(sqls));
        write(dir.resolve("captured_http.json"), "[]");
        write(dir.resolve("endpoints.json"), "[]");
        write(dir.resolve("paths.json"), "[]");
        System.err.println("[graphrag]   path=" + pathId + "  sqls=" + sqls.size());
    }

    private static void writeStats(Path root) {
        try {
            String body = "{\"bridge_calls\":" + JdbcAgentBaggageBridge.CALLS.get()
                    + ",\"resolved_by_correlation\":" + JdbcAgentBaggageBridge.RESOLVED_BY_CORR.get()
                    + ",\"resolved_by_baggage\":" + JdbcAgentBaggageBridge.RESOLVED_BY_BAGGAGE.get()
                    + ",\"dropped_no_pathid\":" + JdbcAgentBaggageBridge.DROPPED_NO_PATHID.get()
                    + ",\"baggage_class_not_found\":" + JdbcAgentBaggageBridge.BAGGAGE_NO_CLASS.get()
                    + ",\"baggage_entry_missing\":" + JdbcAgentBaggageBridge.BAGGAGE_NO_VALUE.get() + "}";
            Files.writeString(root.resolve(".stats.json"), body);
        } catch (Throwable ignored) {}
    }

    private static void write(Path file, String body) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(body);
            w.newLine();
        }
    }

    // ----- JSON emission -----

    static String sqlsJson(List<CapturedSql> sqls) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("[");
        for (int i = 0; i < sqls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\n  ");
            appendSql(sb, sqls.get(i));
        }
        if (!sqls.isEmpty()) sb.append("\n");
        sb.append("]");
        return sb.toString();
    }

    private static void appendSql(StringBuilder sb, CapturedSql s) {
        sb.append("{");
        kv(sb, "id", s.id()); sb.append(",");
        kv(sb, "path_id", s.pathId()); sb.append(",");
        kv(sb, "type", s.type().name()); sb.append(",");
        kv(sb, "raw_sql", s.rawSql()); sb.append(",");
        sb.append("\"bindings\":");
        appendBindings(sb, s.bindings()); sb.append(",");
        kv(sb, "source", s.source().name()); sb.append(",");
        sb.append("\"source_location\":");
        appendLocation(sb, s.sourceLocation()); sb.append(",");
        sb.append("\"affected_tables\":");
        appendStringList(sb, s.affectedTables()); sb.append(",");
        sb.append("\"affected_columns\":");
        appendStringList(sb, s.affectedColumns()); sb.append(",");
        sb.append("\"read_result_rows\":");
        appendRowList(sb, s.readResultRows());
        sb.append("}");
    }

    private static void appendBindings(StringBuilder sb, List<Binding> bindings) {
        sb.append("[");
        for (int i = 0; i < bindings.size(); i++) {
            if (i > 0) sb.append(",");
            Binding b = bindings.get(i);
            sb.append("{\"position\":").append(b.position())
              .append(",\"value\":");
            appendValue(sb, b.value());
            sb.append(",\"origin\":\"").append(b.origin().name()).append('"')
              .append(",\"origin_ref\":");
            appendValue(sb, b.originRef());
            sb.append("}");
        }
        sb.append("]");
    }

    private static void appendLocation(StringBuilder sb, SourceLocation l) {
        sb.append("{");
        kv(sb, "class", l.className()); sb.append(",");
        kv(sb, "method", l.method()); sb.append(",");
        sb.append("\"line\":").append(l.line());
        sb.append("}");
    }

    private static void appendStringList(StringBuilder sb, Collection<String> xs) {
        sb.append("[");
        int i = 0;
        for (String x : xs) {
            if (i++ > 0) sb.append(",");
            sb.append('"').append(escape(x)).append('"');
        }
        sb.append("]");
    }

    private static void appendRowList(StringBuilder sb, List<Map<String, Object>> rows) {
        sb.append("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            int j = 0;
            for (var e : rows.get(i).entrySet()) {
                if (j++ > 0) sb.append(",");
                sb.append('"').append(escape(e.getKey())).append("\":");
                appendValue(sb, e.getValue());
            }
            sb.append("}");
        }
        sb.append("]");
    }

    /** Appends a JSON-encoded scalar (null, number, boolean, string). Unknown types → toString. */
    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Number) { sb.append(v); return; }
        if (v instanceof Boolean) { sb.append(((Boolean) v) ? "true" : "false"); return; }
        sb.append('"').append(escape(v.toString())).append('"');
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append('"').append(k).append("\":");
        if (v == null) sb.append("null");
        else sb.append('"').append(escape(v)).append('"');
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
