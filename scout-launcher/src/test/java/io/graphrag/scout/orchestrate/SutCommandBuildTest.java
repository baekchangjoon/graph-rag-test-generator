package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.SutConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SutCommandBuildTest {

    @Test
    void jar_mode_command_includes_agents_bootclasspath_props_and_args() {
        SutConfig cfg = new SutConfig(
            "/tmp/sut.jar", null, List.of(),
            List.of("--server.port=8084"),
            List.of("-Xmx512m"),
            List.of("/tmp/agent.jar"),
            List.of("/tmp/bridge.jar", "/tmp/api.jar"),
            Map.of("spring.profiles.active", "postgres"),
            null
        );
        var orch = new SutProcessOrchestrator(cfg, "/tmp/out");
        List<String> cmd = orch.buildCommand();

        // First arg is the java binary
        assertThat(cmd.get(0)).endsWith("java");
        // -javaagent + -Xbootclasspath/a + jvm-args + -D + -jar + sut.jar + sut args
        assertThat(cmd).anyMatch(a -> a.startsWith("-javaagent:") && a.endsWith("agent.jar"));
        assertThat(cmd).anyMatch(a -> a.startsWith("-Xbootclasspath/a:")
            && a.contains("bridge.jar") && a.contains("api.jar"));
        assertThat(cmd).contains("-Xmx512m");
        assertThat(cmd).anyMatch(a -> a.equals("-Dgraphrag.archive.output.dir=" + java.nio.file.Path.of("/tmp/out").toAbsolutePath()));
        assertThat(cmd).anyMatch(a -> a.equals("-Dspring.profiles.active=postgres"));
        assertThat(cmd).contains("-jar");
        assertThat(cmd).anyMatch(a -> a.endsWith("sut.jar"));
        assertThat(cmd).endsWith("--server.port=8084");
    }

    @Test
    void main_class_mode_uses_cp_and_main_class() {
        SutConfig cfg = new SutConfig(
            null, "com.example.Main",
            List.of("/tmp/app.jar", "/tmp/lib.jar"),
            List.of("arg1"),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            null
        );
        var orch = new SutProcessOrchestrator(cfg, "/tmp/out");
        List<String> cmd = orch.buildCommand();
        int cpIdx = cmd.indexOf("-cp");
        assertThat(cpIdx).isPositive();
        assertThat(cmd.get(cpIdx + 1)).contains("app.jar").contains("lib.jar");
        assertThat(cmd.get(cpIdx + 2)).isEqualTo("com.example.Main");
        assertThat(cmd).endsWith("arg1");
    }
}
