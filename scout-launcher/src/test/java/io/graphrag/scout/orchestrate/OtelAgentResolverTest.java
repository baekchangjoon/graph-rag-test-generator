package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.OtelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelAgentResolverTest {

    @Test
    void uses_explicit_agent_path_when_set(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("agent.jar");
        Files.writeString(jar, "x");
        OtelConfig cfg = new OtelConfig(true, null, null, jar.toString(), null, null, null);
        Path resolved = new OtelAgentResolver(cfg).resolveAgentJar();
        assertThat(resolved).isEqualTo(jar.toAbsolutePath());
    }

    @Test
    void rejects_explicit_path_that_does_not_exist(@TempDir Path tmp) {
        OtelConfig cfg = new OtelConfig(true, null, null, tmp.resolve("nope.jar").toString(),
                                         null, null, null);
        assertThatThrownBy(() -> new OtelAgentResolver(cfg).resolveAgentJar())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void system_properties_enable_baggage_disable_exporters_and_always_capture() {
        var props = new OtelAgentResolver(
                new OtelConfig(true, "2.28.1", "1.45.0", null, null, null, "my-svc"))
            .systemProperties();
        assertThat(props)
            .containsEntry("otel.propagators", "baggage")
            .containsEntry("otel.traces.exporter", "none")
            .containsEntry("otel.metrics.exporter", "none")
            .containsEntry("otel.logs.exporter", "none")
            .containsEntry("otel.service.name", "my-svc")
            .containsEntry("jdbcintercept.capture.always", "true");
    }

    @Test
    void cached_path_uses_user_home_and_version() {
        Path p = OtelAgentResolver.cachedAgentPath("2.28.1");
        assertThat(p.toString())
            .endsWith(".cache/graphrag-scout/otel-javaagent-2.28.1.jar");
    }
}
