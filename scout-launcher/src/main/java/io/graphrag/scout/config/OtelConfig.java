package io.graphrag.scout.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenTelemetry javaagent integration — propagates the scout's per-step path-id from
 * the HTTP handler thread to the JDBC handler thread inside the SUT via OTEL Baggage.
 *
 * <p>When {@link #enabled()} is true, {@code SutProcessOrchestrator} prepends the OTEL
 * javaagent to the SUT command line (downloading once into
 * {@code ~/.cache/graphrag-scout/otel-javaagent-{version}.jar} if missing) and injects
 * a minimal set of system properties that enable baggage propagation while disabling
 * every exporter (no traces / metrics / logs leave the SUT).
 *
 * <p>Required because {@code jdbc-intercept-agent} on its own captures only when
 * {@code JdbcCaptureSession.begin(pathId)} was called on the same thread that runs
 * the JDBC call. For HTTP-driven scout that means we need the bridge to recover the
 * pathId from baggage on the Tomcat handler thread — which only works if OTEL's
 * Servlet instrumentation has restored baggage from the inbound {@code baggage:} header.
 */
public record OtelConfig(
    @JsonProperty("enabled")         Boolean enabled,
    @JsonProperty("version")         String version,
    @JsonProperty("api-version")     String apiVersion,
    @JsonProperty("agent-path")      String agentPath,
    @JsonProperty("api-jar")         String apiJar,
    @JsonProperty("context-jar")     String contextJar,
    @JsonProperty("service-name")    String serviceName
) {
    public static final String DEFAULT_VERSION = "2.28.1";
    public static final String DEFAULT_API_VERSION = "1.45.0";
    public static final String DEFAULT_SERVICE_NAME = "scout-sut";

    public static final OtelConfig DISABLED =
            new OtelConfig(false, null, null, null, null, null, null);

    public OtelConfig {
        if (enabled == null) enabled = Boolean.FALSE;
        if (version == null || version.isBlank()) version = DEFAULT_VERSION;
        if (apiVersion == null || apiVersion.isBlank()) apiVersion = DEFAULT_API_VERSION;
        if (serviceName == null || serviceName.isBlank()) serviceName = DEFAULT_SERVICE_NAME;
    }
}
