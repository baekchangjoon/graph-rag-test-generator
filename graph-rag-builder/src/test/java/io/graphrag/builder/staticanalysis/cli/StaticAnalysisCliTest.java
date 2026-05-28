package io.graphrag.builder.staticanalysis.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAnalysisCliTest {

    private static Path fixture() {
        return Path.of("src/test/resources/staticanalysis/petclinic-fixture");
    }

    private static int run(String[] args, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        return StaticAnalysisCli.run(args, new PrintStream(out), new PrintStream(err));
    }

    @Test
    void cli_writes_three_json_files(@TempDir Path tmp) {
        Path outDir = tmp.resolve("archive");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, out, err);
        assertThat(code).isZero();
        assertThat(Files.exists(outDir.resolve("endpoints.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("paths.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("static-analysis-report.json"))).isTrue();
    }

    @Test
    void endpoints_json_parses_as_endpoint_list(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        List<Endpoint> endpoints = m.readValue(
                outDir.resolve("endpoints.json").toFile(),
                new TypeReference<>() {});
        assertThat(endpoints).isNotEmpty();
        assertThat(endpoints).extracting(Endpoint::id)
                .allMatch(id -> id.matches("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS):/.+$"));
    }

    @Test
    void paths_json_parses_as_explored_path_list(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        List<ExploredPath> paths = m.readValue(
                outDir.resolve("paths.json").toFile(),
                new TypeReference<>() {});
        assertThat(paths).isNotEmpty();
        assertThat(paths).extracting(ExploredPath::id)
                .allMatch(id -> id.startsWith("static_"));
    }

    @Test
    void report_json_has_expected_top_level_keys(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        Map<String, Object> report = m.readValue(
                outDir.resolve("static-analysis-report.json").toFile(),
                new TypeReference<>() {});
        assertThat(report).containsKeys(
                "executionTimestamp", "executionDurationMs",
                "codeVersion", "project",
                "parsing", "analysis", "pathGeneration", "manualReviewQueue");
    }

    @Test
    void each_endpoint_has_at_least_one_path(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        List<Endpoint> endpoints = m.readValue(
                outDir.resolve("endpoints.json").toFile(), new TypeReference<>() {});
        List<ExploredPath> paths = m.readValue(
                outDir.resolve("paths.json").toFile(), new TypeReference<>() {});
        for (Endpoint ep : endpoints) {
            assertThat(paths).as("paths for endpoint %s", ep.id())
                    .anyMatch(p -> p.endpointId().equals(ep.id()));
        }
    }

    @Test
    void excludePaths_argument_filters_output(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString(),
                "--exclude-paths", "GET:/owners"
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        List<Endpoint> endpoints = m.readValue(
                outDir.resolve("endpoints.json").toFile(), new TypeReference<>() {});
        List<ExploredPath> paths = m.readValue(
                outDir.resolve("paths.json").toFile(), new TypeReference<>() {});
        assertThat(endpoints).extracting(Endpoint::id).doesNotContain("GET:/owners");
        assertThat(paths).extracting(ExploredPath::endpointId).doesNotContain("GET:/owners");
    }

    @Test
    void idempotent_two_runs_same_bytes_for_endpoints_and_paths(@TempDir Path tmp) throws Exception {
        Path runA = tmp.resolve("a");
        Path runB = tmp.resolve("b");
        for (Path d : new Path[] { runA, runB }) {
            run(new String[] {
                    "--sut-source", fixture().toString(),
                    "--project", "petclinic",
                    "--out", d.toString()
            }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        }
        assertThat(Files.readAllBytes(runA.resolve("endpoints.json")))
                .isEqualTo(Files.readAllBytes(runB.resolve("endpoints.json")));
        assertThat(Files.readAllBytes(runA.resolve("paths.json")))
                .isEqualTo(Files.readAllBytes(runB.resolve("paths.json")));
    }

    @Test
    void missing_required_flag_exits_2(@TempDir Path tmp) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(new String[] { "--sut-source", fixture().toString() },
                new ByteArrayOutputStream(), err);
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("--project").contains("usage");
    }
}
