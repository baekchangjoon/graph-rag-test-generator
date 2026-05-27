package io.graphrag.scout.orchestrate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.JsonMappers;
import io.graphrag.scout.config.CaptureStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutMetadataWriterTest {

    private static final ObjectMapper M = JsonMappers.standard();

    @Test
    void writes_endpoint_and_path_per_step(@TempDir Path tmp) throws Exception {
        ScoutResult r = new ScoutResult(
                new CaptureStep("list-owners", "GET", "/api/owners", null, null,
                                Map.of(), 200),
                Map.of("X-Graphrag-Path-Id", "list-owners"),
                null,
                200,
                Map.of("Content-Type", "application/json"),
                "[{\"id\":1,\"first_name\":\"George\"}]");

        new ScoutMetadataWriter(tmp, "petclinic").write(List.of(r));

        Path dir = tmp.resolve("list-owners");
        List<Endpoint> endpoints = M.readValue(
                Files.readAllBytes(dir.resolve("endpoints.json")),
                new TypeReference<>() {});
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).id()).isEqualTo("GET:/api/owners");
        assertThat(endpoints.get(0).method()).isEqualTo(HttpMethod.GET);
        assertThat(endpoints.get(0).project()).isEqualTo("petclinic");

        List<ExploredPath> paths = M.readValue(
                Files.readAllBytes(dir.resolve("paths.json")),
                new TypeReference<>() {});
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).endpointId()).isEqualTo("GET:/api/owners");
        assertThat(paths.get(0).exitStatus()).isEqualTo(200);
        assertThat(paths.get(0).sampleInput().headers())
                .containsEntry("X-Graphrag-Path-Id", "list-owners");

        // Response body parsed as JSON, not kept as raw string
        assertThat(paths.get(0).exitResponseShape()).isInstanceOf(List.class);

        // captured_http stub
        List<?> http = M.readValue(
                Files.readAllBytes(dir.resolve("captured_http.json")),
                new TypeReference<List<Object>>() {});
        assertThat(http).isEmpty();
    }

    @Test
    void keeps_non_json_body_as_raw_string(@TempDir Path tmp) throws Exception {
        ScoutResult r = new ScoutResult(
                new CaptureStep("health", "GET", "/actuator/health", null, null,
                                Map.of(), 0),
                Map.of(), null, 200, Map.of(), "UP");
        new ScoutMetadataWriter(tmp, null).write(List.of(r));
        List<ExploredPath> paths = M.readValue(
                Files.readAllBytes(tmp.resolve("health/paths.json")),
                new TypeReference<>() {});
        assertThat(paths.get(0).exitResponseShape()).isEqualTo("UP");
    }

    @Test
    void writes_into_dir_that_already_has_captured_sql(@TempDir Path tmp) throws Exception {
        // Simulate the SUT-side bridge having already written captured_sql.json before
        // ScoutMetadataWriter runs.
        Path dir = tmp.resolve("p1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("captured_sql.json"), "[]");

        ScoutResult r = new ScoutResult(
                new CaptureStep("p1", "GET", "/x", null, null, Map.of(), 0),
                Map.of(), null, 200, Map.of(), "");
        new ScoutMetadataWriter(tmp, "proj").write(List.of(r));

        assertThat(Files.readString(dir.resolve("captured_sql.json"))).isEqualTo("[]");
        assertThat(Files.exists(dir.resolve("endpoints.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("paths.json"))).isTrue();
    }
}
