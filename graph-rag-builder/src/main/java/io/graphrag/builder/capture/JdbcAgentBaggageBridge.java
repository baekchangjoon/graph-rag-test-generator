package io.graphrag.builder.capture;

import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.SourceLocation;
import io.jdbcintercept.api.BindingValue;
import io.jdbcintercept.api.CapturedQuery;
import io.jdbcintercept.api.JdbcCaptureListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bridge — receives {@link CapturedQuery} events from
 * {@code jdbc-intercept-agent} via its {@code ServiceLoader} SPI, and writes
 * {@link CapturedSql} (with snapshotRows mapped to {@code readResultRows} for Option A)
 * into the matching {@link CaptureContext} held in {@link CaptureContextRegistry}.
 *
 * <p>Path-id resolution order:
 * <ol>
 *   <li>{@code CapturedQuery.correlationId()} — set by
 *       {@code JdbcCaptureSession.begin(pathId)} when the SUT was invoked from the
 *       analysis thread directly</li>
 *   <li>OpenTelemetry Baggage entry {@code graphrag.path-id} — set by the scout test
 *       via {@code Baggage.builder().put(...).build().makeCurrent()}, propagated
 *       across thread boundaries by the OTEL javaagent's ContextStorage so the
 *       Servlet handler thread sees it</li>
 * </ol>
 *
 * <p>OTEL is consulted via reflection so that this class compiles & runs when
 * {@code io.opentelemetry.api} is not on the classpath (degrades to ThreadLocal-only
 * resolution).
 */
public final class JdbcAgentBaggageBridge implements JdbcCaptureListener {

    /** Baggage key the scout test sets and this bridge reads. */
    public static final String BAGGAGE_KEY = "graphrag.path-id";

    public JdbcAgentBaggageBridge() {}

    @Override
    public void afterQuery(CapturedQuery q) {
        String pathId = q.correlationId();
        if (pathId == null || pathId.isEmpty() || "null".equals(pathId)) {
            pathId = readBaggageReflectively(BAGGAGE_KEY);
        }
        if (pathId == null) return;

        CaptureContext ctx = CaptureContextRegistry.forPathId(pathId);
        if (ctx == null) return;

        ctx.addCapturedSql(toCapturedSql(pathId, q));
    }

    static CapturedSql toCapturedSql(String pathId, CapturedQuery q) {
        CapturedSqlType type = detectType(q.sql());
        List<io.graphrag.model.Binding> bindings = new ArrayList<>(q.bindings().size());
        for (BindingValue b : q.bindings()) {
            bindings.add(new io.graphrag.model.Binding(
                    b.index() - 1,   // graph-rag uses 0-based; agent uses JDBC's 1-based
                    b.value(),
                    BindingOrigin.COMPUTED,
                    null));
        }
        CapturedSqlSource source = q.mybatisMeta().isPresent()
                ? CapturedSqlSource.MYBATIS_XML_MAPPER
                : CapturedSqlSource.JDBC_RAW;
        SourceLocation loc = q.mybatisMeta()
                .map(m -> new SourceLocation(m.namespace(), m.mapperId(), -1))
                .orElse(new SourceLocation("agent", "unknown", -1));
        return new CapturedSql(
                "sql-" + UUID.randomUUID(),
                pathId,
                type,
                q.sql(),
                bindings,
                source,
                loc,
                extractTables(q.sql()),
                List.of(),
                q.snapshotRows());
    }

    private static CapturedSqlType detectType(String sql) {
        if (sql == null) return CapturedSqlType.DDL;
        String t = sql.stripLeading().toUpperCase();
        if (t.startsWith("SELECT")) return CapturedSqlType.SELECT;
        if (t.startsWith("INSERT")) return CapturedSqlType.INSERT;
        if (t.startsWith("UPDATE")) return CapturedSqlType.UPDATE;
        if (t.startsWith("DELETE")) return CapturedSqlType.DELETE;
        return CapturedSqlType.DDL;
    }

    private static final java.util.regex.Pattern TABLE_AFTER_VERB = java.util.regex.Pattern.compile(
            "(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+([\\w.\"`]+)");

    static List<String> extractTables(String sql) {
        if (sql == null) return List.of();
        List<String> out = new ArrayList<>();
        var m = TABLE_AFTER_VERB.matcher(sql);
        while (m.find()) {
            String name = m.group(1).replace("\"", "").replace("`", "");
            if (!out.contains(name)) out.add(name);
        }
        return out;
    }

    /**
     * Reads OTEL Baggage current entry by key, via reflection so the bridge does not
     * hard-require {@code io.opentelemetry.api} on the classpath.
     */
    static String readBaggageReflectively(String key) {
        try {
            Class<?> baggageCls = Class.forName("io.opentelemetry.api.baggage.Baggage");
            Object current = baggageCls.getMethod("current").invoke(null);
            // Resolve via interface so package-private impls dispatch correctly.
            Object value = baggageCls.getMethod("getEntryValue", String.class).invoke(current, key);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
