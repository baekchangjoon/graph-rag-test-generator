package io.graphrag.scout.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @Test
    void loads_full_config(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("c.yml");
        Files.writeString(cfg, """
            sut:
              jar: /tmp/sut.jar
              args: ["--server.port=8080"]
              agents: ["/tmp/agent.jar"]
              boot-classpath: ["/tmp/bridge.jar"]
              system-properties:
                spring.profiles.active: postgres
              health-check:
                url: http://localhost:8080/actuator/health
                timeout-seconds: 30
                interval-millis: 500
            dependencies:
              docker-compose:
                file: ./docker-compose.yml
                services: [postgres]
                wait-for-healthy: true
                teardown-on-exit: false
                health-timeout-seconds: 60
            scout:
              base-url: http://localhost:8080
              steps:
                - path-id: a
                  method: GET
                  path: /api/x
                - path-id: b
                  method: POST
                  path: /api/x
                  body: '{"y":1}'
                  content-type: application/json
                  expected-status: 201
            output:
              archive-dir: /tmp/out
              clear-before-run: true
            """);

        ScoutConfig c = ConfigLoader.load(cfg);
        assertThat(c.sut().jar()).isEqualTo("/tmp/sut.jar");
        assertThat(c.sut().agents()).containsExactly("/tmp/agent.jar");
        assertThat(c.sut().bootClasspath()).containsExactly("/tmp/bridge.jar");
        assertThat(c.sut().systemProperties()).containsEntry("spring.profiles.active", "postgres");
        assertThat(c.sut().healthCheck().timeoutSeconds()).isEqualTo(30);
        assertThat(c.dependencies().dockerCompose().services()).containsExactly("postgres");
        assertThat(c.dependencies().dockerCompose().teardownOnExit()).isFalse();
        assertThat(c.scout().steps()).hasSize(2);
        assertThat(c.scout().steps().get(1).expectedStatus()).isEqualTo(201);
        assertThat(c.scout().steps().get(1).contentType()).isEqualTo("application/json");
        assertThat(c.output().archiveDir()).isEqualTo("/tmp/out");
        assertThat(c.output().clearBeforeRun()).isTrue();
    }

    @Test
    void defaults_apply_when_optional_fields_missing(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("min.yml");
        Files.writeString(cfg, """
            sut:
              main-class: com.example.Main
              classpath: ["/tmp/x.jar"]
            scout:
              base-url: http://localhost:8080
              steps:
                - path-id: a
                  path: /
            output:
              archive-dir: /tmp/out
            """);
        ScoutConfig c = ConfigLoader.load(cfg);
        assertThat(c.dependencies()).isNull();
        assertThat(c.sut().healthCheck().timeoutSeconds()).isEqualTo(60);
        assertThat(c.sut().healthCheck().intervalMillis()).isEqualTo(1000);
        assertThat(c.scout().steps().get(0).method()).isEqualTo("GET");
        assertThat(c.scout().steps().get(0).expectedStatus()).isZero();
        assertThat(c.output().clearBeforeRun()).isFalse();
    }

    @Test
    void rejects_sut_without_jar_or_main_class(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("bad.yml");
        Files.writeString(cfg, """
            sut: {}
            scout:
              base-url: http://localhost
              steps: []
            output:
              archive-dir: /tmp
            """);
        assertThatThrownBy(() -> ConfigLoader.load(cfg))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("one of {jar, main-class}");
    }

    @Test
    void rejects_missing_config_file(@TempDir Path tmp) {
        assertThatThrownBy(() -> ConfigLoader.load(tmp.resolve("nope.yml")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }
}
