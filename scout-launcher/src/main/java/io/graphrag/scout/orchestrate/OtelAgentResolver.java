package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.OtelConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the OpenTelemetry javaagent jar for the SUT and returns the
 * system properties needed to wire up baggage propagation with all exporters off.
 *
 * <p>Caches under {@code ~/.cache/graphrag-scout/otel-javaagent-{version}.jar} so the
 * download happens once per developer machine — no network roundtrip on subsequent runs.
 */
public final class OtelAgentResolver {

    private static final String AGENT_URL_TEMPLATE =
        "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v%s/opentelemetry-javaagent.jar";

    private static final String API_URL_TEMPLATE =
        "https://repo1.maven.org/maven2/io/opentelemetry/opentelemetry-api/%s/opentelemetry-api-%s.jar";

    private static final String CONTEXT_URL_TEMPLATE =
        "https://repo1.maven.org/maven2/io/opentelemetry/opentelemetry-context/%s/opentelemetry-context-%s.jar";

    private final OtelConfig cfg;

    public OtelAgentResolver(OtelConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Returns the absolute path to a usable {@code opentelemetry-javaagent.jar}, downloading
     * it into the local cache if it does not already exist there. If
     * {@link OtelConfig#agentPath()} is set, that path is used directly with no download.
     */
    public Path resolveAgentJar() throws IOException, InterruptedException {
        return resolveOne(
            cfg.agentPath(), "otel.agent-path",
            cachedAgentPath(cfg.version()),
            String.format(AGENT_URL_TEMPLATE, cfg.version()));
    }

    /**
     * Resolves the {@code opentelemetry-api} jar — required on the SUT's bootclasspath so
     * the bridge's reflection finds {@code io.opentelemetry.api.baggage.Baggage}. The OTEL
     * javaagent bridges its shaded impl into this no-op API at runtime.
     */
    public Path resolveApiJar() throws IOException, InterruptedException {
        return resolveOne(
            cfg.apiJar(), "otel.api-jar",
            cachedJarPath("opentelemetry-api", cfg.apiVersion()),
            String.format(API_URL_TEMPLATE, cfg.apiVersion(), cfg.apiVersion()));
    }

    /** Resolves the {@code opentelemetry-context} jar — transitive dep of opentelemetry-api. */
    public Path resolveContextJar() throws IOException, InterruptedException {
        return resolveOne(
            cfg.contextJar(), "otel.context-jar",
            cachedJarPath("opentelemetry-context", cfg.apiVersion()),
            String.format(CONTEXT_URL_TEMPLATE, cfg.apiVersion(), cfg.apiVersion()));
    }

    private static Path resolveOne(String configured, String configName,
                                   Path cache, String downloadUrl)
            throws IOException, InterruptedException {
        if (configured != null && !configured.isBlank()) {
            Path p = Paths.get(configured).toAbsolutePath();
            if (!Files.exists(p)) {
                throw new IOException(configName + " does not exist: " + p);
            }
            return p;
        }
        if (Files.exists(cache) && Files.size(cache) > 0) return cache;
        download(downloadUrl, cache);
        return cache;
    }

    /**
     * The fixed set of {@code -D} props the launcher must inject when OTEL is enabled.
     * Order is irrelevant; using a {@link LinkedHashMap} only to keep deterministic
     * logging when the launcher prints the command line.
     *
     * <p>Exporters are all set to {@code none} so the agent stays passive — we only need
     * baggage propagation, not actual telemetry export. {@code otel.javaagent.debug} can
     * be flipped via the env-var override if a developer needs verbose agent logs.
     */
    public Map<String, String> systemProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("otel.javaagent.debug", "false");
        p.put("otel.propagators", "baggage");
        p.put("otel.traces.exporter", "none");
        p.put("otel.metrics.exporter", "none");
        p.put("otel.logs.exporter", "none");
        p.put("otel.service.name", cfg.serviceName());
        // Disable OTEL's own JDBC instrumentation — it conflicts with jdbc-intercept-agent's
        // ByteBuddy matchers (shaded TypePool can't resolve OTEL's virtual-field accessor
        // helpers, polluting the log with TypePool$NoSuchTypeException WARNs and, more
        // importantly, the dual transformation breaks advice dispatch).
        p.put("otel.instrumentation.jdbc.enabled", "false");
        p.put("otel.instrumentation.jdbc-datasource.enabled", "false");
        // Servlet instrumentation must stay ON — that is what restores the inbound `baggage`
        // header onto the Tomcat handler thread's Context so the bridge can read it.
        // jdbc-intercept-agent needs to know capture is always-on so its advice does not
        // short-circuit when the Tomcat handler thread has no JdbcCaptureSession active.
        // The bridge (graph-rag-builder) resolves the pathId from baggage inside afterQuery.
        p.put("jdbcintercept.capture.always", "true");
        return p;
    }

    static Path cachedAgentPath(String version) {
        return cachedJarPath("otel-javaagent", version);
    }

    static Path cachedJarPath(String artifact, String version) {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".cache", "graphrag-scout",
                         artifact + "-" + version + ".jar");
    }

    private static void download(String url, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        System.out.println("[scout] downloading " + url + " -> " + target);
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
        HttpResponse<InputStream> resp = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("download failed (" + resp.statusCode() + "): " + url);
        }
        Path tmp = Files.createTempFile(target.getParent(), "otel-", ".jar.part");
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
