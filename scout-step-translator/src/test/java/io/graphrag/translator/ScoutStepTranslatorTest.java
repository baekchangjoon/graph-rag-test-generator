package io.graphrag.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.graphrag.scout.config.ScoutConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutStepTranslatorTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void translates_petclinic_manual_seed_into_scout_launcher_parseable_yaml(@TempDir Path tmp)
            throws Exception {
        Path seed = locateRepoFile("samples/scout/petclinic/manual-archive-seed");
        Path template = copyTemplateInto(tmp);
        Path out = tmp.resolve("generated-config.yml");

        int exit = ScoutStepTranslator.run(new String[] {
                "--paths-file",          seed.resolve("paths.json").toString(),
                "--endpoints-file",      seed.resolve("endpoints.json").toString(),
                "--scout-base-url",      "http://localhost:8084",
                "--sut-config-template", template.toString(),
                "--out",                 out.toString()
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(Files.exists(out)).isTrue();

        // Round-trip: the output must parse cleanly through scout-launcher's own ScoutConfig
        // record. This is the contract test — if a field rename breaks the join, we catch it
        // here instead of when scout-launcher boots in production.
        ScoutConfig cfg = YAML.readValue(Files.readAllBytes(out), ScoutConfig.class);

        assertThat(cfg.scout().baseUrl()).isEqualTo("http://localhost:8084");
        assertThat(cfg.scout().steps()).hasSize(3);

        // Path-id preservation is the load-bearing invariant: paths.json's `id` MUST equal
        // generated step's `path-id` so CapturedSql.path_id joins back unambiguously.
        assertThat(cfg.scout().steps().stream().map(s -> s.pathId()))
                .containsExactly("static_list-owners", "static_get-owner-1", "static_list-vets");

        // pathParam substitution check.
        var getOwner1 = cfg.scout().steps().get(1);
        assertThat(getOwner1.path()).isEqualTo("/api/owners/1");
        assertThat(getOwner1.expectedStatus()).isEqualTo(200);

        // Template's non-scout sections survive untouched.
        assertThat(cfg.sut().jar()).isEqualTo("/opt/sut/app.jar");
        assertThat(cfg.output().archiveDir()).isEqualTo("/tmp/translator-test-archive");
        assertThat(cfg.output().project()).isEqualTo("petclinic");
    }

    @Test
    void exits_nonzero_when_endpoint_id_missing(@TempDir Path tmp) throws Exception {
        Path paths = tmp.resolve("paths.json");
        Files.writeString(paths, """
                [{
                  "id": "p1",
                  "endpoint_id": "GET:/missing",
                  "discovered_by": "MANUAL",
                  "sample_input": {"headers":{},"path_params":{},"query_params":{},"body":null},
                  "branches_taken": [],
                  "exit_status": 200,
                  "coverage_signature": "sig",
                  "code_version": "v1"
                }]
                """);
        Path endpoints = tmp.resolve("endpoints.json");
        Files.writeString(endpoints, "[]");
        Path template = copyTemplateInto(tmp);

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = ScoutStepTranslator.run(new String[] {
                "--paths-file",          paths.toString(),
                "--endpoints-file",      endpoints.toString(),
                "--scout-base-url",      "http://localhost:8084",
                "--sut-config-template", template.toString(),
                "--out",                 tmp.resolve("out.yml").toString()
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errBuf));

        assertThat(exit).isEqualTo(3);
        assertThat(errBuf.toString()).contains("GET:/missing");
    }

    @Test
    void exits_2_when_required_flag_missing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = ScoutStepTranslator.run(new String[] { "--paths-file", "x.json" },
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errBuf));
        assertThat(exit).isEqualTo(2);
        assertThat(errBuf.toString()).contains("missing required flag");
    }

    private static Path copyTemplateInto(Path tmp) throws Exception {
        Path src = locateResource("template.yml");
        Path dst = tmp.resolve("template.yml");
        Files.copy(src, dst);
        return dst;
    }

    private static Path locateResource(String name) {
        try {
            return Paths.get(ScoutStepTranslatorTest.class
                    .getClassLoader().getResource(name).toURI());
        } catch (Exception ex) {
            throw new IllegalStateException("resource not found: " + name, ex);
        }
    }

    private static Path locateRepoFile(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) return direct;
        Path up = Paths.get("..", relative);
        if (Files.exists(up)) return up;
        throw new IllegalStateException("could not locate " + relative + " (cwd="
                + Paths.get("").toAbsolutePath() + ")");
    }
}
