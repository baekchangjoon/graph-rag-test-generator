package io.graphrag.builder.store;

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
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import io.graphrag.model.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileGraphStoreTest {

    private final Endpoint ep = new Endpoint(
            "POST:/api/orders", HttpMethod.POST, "/api/orders",
            "demo", "C", "m", false, List.of());

    private final ExploredPath path = new ExploredPath("p1", ep.id(), PathExplorerKind.MANUAL,
            new SampleInput(Map.of(), Map.of(), Map.of(), Map.of()),
            null, List.of(), 201, null, "sig", "v1");

    @Test
    void roundTripsEndpointPathSqlHttpThroughGraphStoreApi(@TempDir Path tmp) {
        try (FileGraphStore store = new FileGraphStore(tmp)) {
            store.saveEndpoint(ep);
            store.savePath(path);
            store.saveCapturedSql(new CapturedSql("s-1", "p1", CapturedSqlType.INSERT,
                    "INSERT INTO x VALUES (?)",
                    List.of(new Binding(0, 1, BindingOrigin.COMPUTED, null)),
                    CapturedSqlSource.JPA_ENTITYMANAGER,
                    new SourceLocation("X", "y", 1), List.of("x"), List.of()));
            store.saveCapturedHttpCall(new CapturedHttpCall("h-1", "p1", "GET",
                    "/api", "/api", List.of(), Map.of(), null, List.of(),
                    200, "{}", List.of(), HttpClientType.OTHER, "ext"));
        }

        try (FileGraphStore reload = new FileGraphStore(tmp)) {
            assertThat(reload.findEndpoint(ep.id())).isPresent();
            assertThat(reload.pathsByEndpoint(ep.id())).hasSize(1);
            assertThat(reload.capturedSqlByPath("p1")).hasSize(1);
            assertThat(reload.capturedHttpByPath("p1")).hasSize(1);
            assertThat(reload.findPath("p1")).isPresent();
        }
    }

    @Test
    void emptyDirYieldsEmptyStore(@TempDir Path tmp) {
        try (FileGraphStore store = new FileGraphStore(tmp)) {
            assertThat(store.allEndpoints()).isEmpty();
            assertThat(store.findEndpoint("any")).isEmpty();
            assertThat(store.pathsByEndpoint("any")).isEmpty();
        }
    }
}
