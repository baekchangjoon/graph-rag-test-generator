package io.graphrag.generator.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.PathContext;
import io.graphrag.model.Binding;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpClientType;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.JsonMappers;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import io.graphrag.model.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveReaderTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    private void writeJson(Path file, Object data) throws Exception {
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }

    @Test
    void emptyDirYieldsEmptyReader(@TempDir Path tmp) throws Exception {
        ArchiveReader r = ArchiveReader.load(tmp);

        assertThat(r.endpoints()).isEmpty();
        assertThat(r.findEndpoint("any")).isEmpty();
        assertThat(r.pathsByEndpoint("any")).isEmpty();
        assertThat(r.capturedSqlByPath("any")).isEmpty();
        assertThat(r.capturedHttpByPath("any")).isEmpty();
    }

    @Test
    void buildsMultiPathInputForKnownEndpoint(@TempDir Path tmp) throws Exception {
        Endpoint ep = new Endpoint("POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo", "C", "m", false, List.of());
        ExploredPath path = new ExploredPath("p1", ep.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of("amount", 100)),
                null, List.of(), 201, null, "sig", "v1");
        CapturedSql sql = new CapturedSql("sql-1", "p1", CapturedSqlType.INSERT,
                "INSERT INTO orders VALUES (?)",
                List.of(new Binding(0, "x", BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_ENTITYMANAGER,
                new SourceLocation("X", "y", 1),
                List.of("orders"), List.of());
        CapturedHttpCall http = new CapturedHttpCall("h-1", "p1", "GET",
                "/inv?type=A", "/inv?type=A", List.of(), Map.of(),
                null, List.of(), 200, "{\"a\":1}", List.of(),
                HttpClientType.OTHER, "ext");

        writeJson(tmp.resolve("endpoints.json"), List.of(ep));
        writeJson(tmp.resolve("paths.json"), List.of(path));
        writeJson(tmp.resolve("captured_sql.json"), List.of(sql));
        writeJson(tmp.resolve("captured_http.json"), List.of(http));

        ArchiveReader r = ArchiveReader.load(tmp);

        assertThat(r.findEndpoint(ep.id())).isPresent();
        assertThat(r.pathsByEndpoint(ep.id())).hasSize(1);
        assertThat(r.capturedSqlByPath("p1")).hasSize(1);
        assertThat(r.capturedHttpByPath("p1")).hasSize(1);

        MultiPathSynthesisInput input = r.buildInput(ep.id(), "gen");
        assertThat(input.endpoint()).isEqualTo(ep);
        assertThat(input.paths()).hasSize(1);
        PathContext pc = input.paths().get(0);
        assertThat(pc.capturedSql()).hasSize(1);
        assertThat(pc.capturedHttpCalls()).hasSize(1);
    }

    @Test
    void buildInputThrowsWhenEndpointMissing(@TempDir Path tmp) throws Exception {
        ArchiveReader r = ArchiveReader.load(tmp);
        assertThatThrownBy(() -> r.buildInput("UNKNOWN", "gen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }
}
