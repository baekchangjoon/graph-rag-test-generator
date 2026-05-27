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

    /** System property the scout-launcher sets to ask SUT-side bridge to dump archives on shutdown. */
    public static final String ARCHIVE_DIR_PROP = "graphrag.archive.output.dir";

    /** System property fallback path-id when neither correlation nor baggage resolves one. */
    public static final String DEFAULT_PATH_ID_PROP = "graphrag.default.path-id";

    private static final String DEFAULT_PATH_ID = System.getProperty(DEFAULT_PATH_ID_PROP);

    static {
        // Activated only when scout-launcher (or user) passes -Dgraphrag.archive.output.dir=…
        String dir = System.getProperty(ARCHIVE_DIR_PROP);
        if (dir != null && !dir.isBlank()) {
            // Drop a marker file at registration time. Subsequently, the hook itself touches
            // a second marker on entry. Together they distinguish "hook never registered"
            // (no .hook-registered) from "hook never fired" (.hook-registered but no
            // .hook-fired) from "hook fired but writer failed" (both markers + failure log).
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Paths.get(dir));
                java.nio.file.Files.writeString(java.nio.file.Paths.get(dir, ".hook-registered"),
                        Long.toString(System.currentTimeMillis()));
            } catch (Throwable ignored) {}
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> {
                        try {
                            java.nio.file.Files.writeString(java.nio.file.Paths.get(dir, ".hook-fired"),
                                    Long.toString(System.currentTimeMillis()));
                        } catch (Throwable ignored) {}
                        ArchiveShutdownWriter.dump(dir);
                    },
                    "graphrag-archive-dump"));
        }
    }

    public JdbcAgentBaggageBridge() {}

    /** Diagnostic counters — written into the {@code .stats.json} file at shutdown. */
    static final java.util.concurrent.atomic.AtomicLong CALLS = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong RESOLVED_BY_CORR = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong RESOLVED_BY_BAGGAGE = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong DROPPED_NO_PATHID = new java.util.concurrent.atomic.AtomicLong();

    @Override
    public void afterQuery(CapturedQuery q) {
        CALLS.incrementAndGet();
        String pathId = q.correlationId();
        boolean fromCorr = pathId != null && !pathId.isEmpty() && !"null".equals(pathId);
        if (!fromCorr) {
            pathId = readBaggageReflectively(BAGGAGE_KEY);
        }
        if (pathId == null) pathId = DEFAULT_PATH_ID;
        if (pathId == null) { DROPPED_NO_PATHID.incrementAndGet(); return; }
        (fromCorr ? RESOLVED_BY_CORR : RESOLVED_BY_BAGGAGE).incrementAndGet();

        // Auto-create context on first capture for this pathId. Out-of-process scout never
        // calls CaptureContextRegistry.register() — capture is the only signal of an active path.
        CaptureContext ctx = CaptureContextRegistry.computeIfAbsent(pathId);

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
     *
     * <p>The bridge typically lives on {@code -Xbootclasspath/a:} (so the agent's listener
     * SPI can load it), while the OTEL javaagent loads {@code io.opentelemetry.api.*} into
     * the system / TCCL. {@link Class#forName(String)} from a bootstrap-loaded class would
     * fail with {@code ClassNotFoundException} on those types, so we walk the classloader
     * chain starting with the thread's context loader.
     */
    static final java.util.concurrent.atomic.AtomicLong BAGGAGE_NO_CLASS = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong BAGGAGE_NO_VALUE = new java.util.concurrent.atomic.AtomicLong();

    static String readBaggageReflectively(String key) {
        Class<?> baggageCls = findClass("io.opentelemetry.api.baggage.Baggage");
        if (baggageCls == null) { BAGGAGE_NO_CLASS.incrementAndGet(); return null; }
        try {
            Object current = baggageCls.getMethod("current").invoke(null);
            Object value = baggageCls.getMethod("getEntryValue", String.class).invoke(current, key);
            if (value == null) BAGGAGE_NO_VALUE.incrementAndGet();
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> findClass(String name) {
        ClassLoader[] loaders = new ClassLoader[] {
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                JdbcAgentBaggageBridge.class.getClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try { return Class.forName(name, false, cl); }
            catch (ClassNotFoundException ignored) {}
        }
        return null;
    }
}
