package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.OtelConfig;
import io.graphrag.scout.config.SutConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SutCommandBuildTest {

    @Test
    void jar_mode_command_includes_agents_bootclasspath_props_and_args() throws Exception {
        SutConfig cfg = new SutConfig(
            "/tmp/sut.jar", null, List.of(),
            List.of("--server.port=8084"),
            List.of("-Xmx512m"),
            List.of("/tmp/agent.jar"),
            List.of("/tmp/bridge.jar", "/tmp/api.jar"),
            Map.of("spring.profiles.active", "postgres"),
            null,
            OtelConfig.DISABLED
        );
        var orch = new SutProcessOrchestrator(cfg, "/tmp/out");
        List<String> cmd = orch.buildCommand();

        assertThat(cmd.get(0)).endsWith("java");
        assertThat(cmd).anyMatch(a -> a.startsWith("-javaagent:") && a.endsWith("agent.jar"));
        assertThat(cmd).anyMatch(a -> a.startsWith("-Xbootclasspath/a:")
            && a.contains("bridge.jar") && a.contains("api.jar"));
        assertThat(cmd).contains("-Xmx512m");
        assertThat(cmd).anyMatch(a -> a.equals("-Dgraphrag.archive.output.dir=" + Path.of("/tmp/out").toAbsolutePath()));
        assertThat(cmd).anyMatch(a -> a.equals("-Dspring.profiles.active=postgres"));
        assertThat(cmd).contains("-jar");
        assertThat(cmd).anyMatch(a -> a.endsWith("sut.jar"));
        assertThat(cmd).endsWith("--server.port=8084");
        // OTEL disabled → no otel.* props
        assertThat(cmd).noneMatch(a -> a.startsWith("-Dotel."));
    }

    @Test
    void main_class_mode_uses_cp_and_main_class() throws Exception {
        SutConfig cfg = new SutConfig(
            null, "com.example.Main",
            List.of("/tmp/app.jar", "/tmp/lib.jar"),
            List.of("arg1"),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            null,
            OtelConfig.DISABLED
        );
        var orch = new SutProcessOrchestrator(cfg, "/tmp/out");
        List<String> cmd = orch.buildCommand();
        int cpIdx = cmd.indexOf("-cp");
        assertThat(cpIdx).isPositive();
        assertThat(cmd.get(cpIdx + 1)).contains("app.jar").contains("lib.jar");
        assertThat(cmd.get(cpIdx + 2)).isEqualTo("com.example.Main");
        assertThat(cmd).endsWith("arg1");
    }

    @Test
    void otel_enabled_prepends_otel_agent_and_baggage_props(@TempDir Path tmp) throws Exception {
        Path fakeOtel = tmp.resolve("opentelemetry-javaagent.jar");
        Path fakeApi = tmp.resolve("opentelemetry-api.jar");
        Path fakeCtx = tmp.resolve("opentelemetry-context.jar");
        Files.writeString(fakeOtel, "FAKE");
        Files.writeString(fakeApi, "FAKE");
        Files.writeString(fakeCtx, "FAKE");
        SutConfig cfg = new SutConfig(
            "/tmp/sut.jar", null, List.of(),
            List.of(), List.of(),
            List.of("/tmp/agent.jar"),
            List.of(),
            Map.of(),
            null,
            new OtelConfig(true, null, null, fakeOtel.toString(),
                           fakeApi.toString(), fakeCtx.toString(), null)
        );
        List<String> cmd = new SutProcessOrchestrator(cfg, "/tmp/out").buildCommand();

        int otelAgentIdx = -1, jdbcAgentIdx = -1;
        for (int i = 0; i < cmd.size(); i++) {
            String a = cmd.get(i);
            if (a.startsWith("-javaagent:") && a.contains("opentelemetry-javaagent")) otelAgentIdx = i;
            if (a.startsWith("-javaagent:") && a.endsWith("agent.jar")) jdbcAgentIdx = i;
        }
        assertThat(otelAgentIdx).isPositive();
        assertThat(jdbcAgentIdx).isGreaterThan(otelAgentIdx);
        // boot-classpath must include OTEL API + context jars so the bridge's reflection can see Baggage
        assertThat(cmd).anyMatch(a -> a.startsWith("-Xbootclasspath/a:")
            && a.contains("opentelemetry-api") && a.contains("opentelemetry-context"));
        assertThat(cmd).contains("-Dotel.propagators=baggage");
        assertThat(cmd).contains("-Dotel.traces.exporter=none");
        assertThat(cmd).contains("-Dotel.metrics.exporter=none");
        assertThat(cmd).contains("-Dotel.logs.exporter=none");
        assertThat(cmd).contains("-Djdbcintercept.capture.always=true");
    }
}
