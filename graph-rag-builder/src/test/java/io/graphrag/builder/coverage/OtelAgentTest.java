package io.graphrag.builder.coverage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OtelAgentTest {
    @Test void otlpEnv_hasExporterAndEndpoint(@org.junit.jupiter.api.io.TempDir Path dir) {
        var env = OtelAgent.prepare(dir).otlpEnv("svc", "http://127.0.0.1:4318");
        assertThat(env).containsEntry("OTEL_TRACES_EXPORTER", "otlp")
                .containsEntry("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf")
                .containsEntry("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318")
                .containsEntry("OTEL_INSTRUMENTATION_JDBC_EXPERIMENTAL_CAPTURE_QUERY_PARAMETERS", "true");
        assertThat(env.get("OTEL_PROPAGATORS")).contains("tracecontext");
        assertThat(env).doesNotContainKey("OTEL_EXPORTER_OTLP_HEADERS");   // 무인증 오버로드
    }

    @Test void otlpEnv_withSecret_addsAuthHeader(@org.junit.jupiter.api.io.TempDir Path dir) {
        var env = OtelAgent.prepare(dir).otlpEnv("svc", "http://host.docker.internal:4318", "s3cr3t");
        assertThat(env).containsEntry("OTEL_TRACES_EXPORTER", "otlp")
                .containsEntry("OTEL_EXPORTER_OTLP_ENDPOINT", "http://host.docker.internal:4318")
                .containsEntry("OTEL_EXPORTER_OTLP_HEADERS", "x-graphrag-token=s3cr3t");
    }
}
