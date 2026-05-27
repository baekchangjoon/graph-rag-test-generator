package io.graphrag.discovery;

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

import static org.assertj.core.api.Assertions.assertThat;

class PathDiscoveryStaticTest {

    private static final ObjectMapper M = JsonMappers.standard();

    @Test
    void cli_writes_paths_and_endpoints_jsons_that_round_trip(@TempDir Path tmp) throws Exception {
        Path samples = locateResource("sample-controllers");

        int exit = PathDiscoveryStatic.run(new String[] {
                "--sut-source", samples.toString(),
                "--project",    "petclinic",
                "--out",        tmp.toString()
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        Path endpointsFile = tmp.resolve("endpoints.json");
        Path pathsFile = tmp.resolve("paths.json");
        assertThat(Files.exists(endpointsFile)).isTrue();
        assertThat(Files.exists(pathsFile)).isTrue();

        // Crucial round-trip — using the SAME shared-model record types the downstream
        // ArchiveReader uses (with snake_case via JsonMappers.standard). If this fails the
        // generated paths.json can never be consumed by test-generator --archive.
        List<Endpoint> endpoints = M.readValue(
                Files.readAllBytes(endpointsFile),
                new TypeReference<>() {});
        List<ExploredPath> paths = M.readValue(
                Files.readAllBytes(pathsFile),
                new TypeReference<>() {});

        assertThat(endpoints).hasSize(8);   // 6 from OwnerController + 2 from VetController
        assertThat(paths.size()).isGreaterThan(endpoints.size()); // boundary variants
        // Every ExploredPath must reference a known endpoint id — orphaned paths would
        // silently disappear in ArchiveReader.
        var endpointIds = endpoints.stream().map(Endpoint::id).toList();
        assertThat(paths).allSatisfy(p -> assertThat(endpointIds).contains(p.endpointId()));
    }

    @Test
    void exclude_paths_omits_listed_endpoints(@TempDir Path tmp) throws Exception {
        Path samples = locateResource("sample-controllers");

        int exit = PathDiscoveryStatic.run(new String[] {
                "--sut-source",     samples.toString(),
                "--project",        "petclinic",
                "--out",            tmp.toString(),
                "--exclude-paths",  "GET:/api/owners,GET:/api/vets"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        List<Endpoint> endpoints = M.readValue(
                Files.readAllBytes(tmp.resolve("endpoints.json")),
                new TypeReference<>() {});
        assertThat(endpoints).noneMatch(e -> e.id().equals("GET:/api/owners"));
        assertThat(endpoints).noneMatch(e -> e.id().equals("GET:/api/vets"));
    }

    @Test
    void exit_2_when_required_flag_missing() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = PathDiscoveryStatic.run(new String[] { "--sut-source", "x" },
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertThat(exit).isEqualTo(2);
        assertThat(err.toString()).contains("missing required flag");
    }

    private static Path locateResource(String name) {
        try {
            return Path.of(PathDiscoveryStaticTest.class.getClassLoader()
                    .getResource(name).toURI());
        } catch (Exception ex) {
            throw new IllegalStateException("missing test resource: " + name, ex);
        }
    }
}
