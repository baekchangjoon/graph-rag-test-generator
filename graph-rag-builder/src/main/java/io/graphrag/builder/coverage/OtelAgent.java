package io.graphrag.builder.coverage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * 번들된 OpenTelemetry javaagent를 추출해 SUT 부착 옵션을 만든다 (docs/06).
 * 트레이스 저장 없음 — baggage propagation만 사용.
 */
public final class OtelAgent {

    private final Path agentJar;

    private OtelAgent(Path agentJar) {
        this.agentJar = agentJar;
    }

    public static OtelAgent prepare(Path workDir) {
        try (InputStream in = OtelAgent.class.getResourceAsStream("/agents/otel-javaagent.jar")) {
            if (in == null) {
                throw new IllegalStateException("bundled otel-javaagent.jar not found in resources");
            }
            Path jar = workDir.resolve("otel-javaagent.jar");
            Files.createDirectories(workDir);
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
            return new OtelAgent(jar);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to prepare otel agent", e);
        }
    }

    public Path agentJar() {
        return agentJar;
    }

    public String javaToolOptions() {
        return "-javaagent:" + agentJar.toAbsolutePath();
    }

    public Map<String, String> env(String serviceName) {
        return Map.of(
                "OTEL_TRACES_EXPORTER", "none",
                "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none",
                "OTEL_PROPAGATORS", "tracecontext,baggage",
                "OTEL_SERVICE_NAME", serviceName);
    }

    public java.util.Map<String, String> otlpEnv(String serviceName, String otlpEndpoint) {
        return otlpEnv(serviceName, otlpEndpoint, null);
    }

    /**
     * @param otlpSecret null이 아니면 {@code OTEL_EXPORTER_OTLP_HEADERS=x-graphrag-token=<secret>}를 추가해
     *                   attach 모드 리시버(wildcard bind)의 per-run shared-secret 인증을 통과시킨다.
     */
    public java.util.Map<String, String> otlpEnv(String serviceName, String otlpEndpoint, String otlpSecret) {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("OTEL_TRACES_EXPORTER", "otlp");
        env.put("OTEL_METRICS_EXPORTER", "none");
        env.put("OTEL_LOGS_EXPORTER", "none");
        env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", otlpEndpoint);
        env.put("OTEL_BSP_SCHEDULE_DELAY", "100");
        env.put("OTEL_INSTRUMENTATION_JDBC_EXPERIMENTAL_CAPTURE_QUERY_PARAMETERS", "true");
        env.put("OTEL_PROPAGATORS", "tracecontext,baggage");
        env.put("OTEL_SERVICE_NAME", serviceName);
        if (otlpSecret != null) {
            env.put("OTEL_EXPORTER_OTLP_HEADERS",
                    io.graphrag.builder.capture.otlp.OtlpTraceReceiver.AUTH_HEADER + "=" + otlpSecret);
        }
        return env;
    }
}
