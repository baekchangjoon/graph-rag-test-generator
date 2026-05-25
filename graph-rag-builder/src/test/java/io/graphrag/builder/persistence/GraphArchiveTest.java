package io.graphrag.builder.persistence;

import io.graphrag.builder.capture.CapturedSqlBuilder;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphArchiveTest {

    private final Endpoint sampleEndpoint = new Endpoint(
            "POST:/api/orders", HttpMethod.POST, "/api/orders",
            "demo-sut", "OrdersController", "createOrder",
            false, List.of());

    private final CapturedSql sampleSql = CapturedSqlBuilder.build(
            "path-1",
            "INSERT INTO orders(id, user_id) VALUES (?, ?)",
            List.of(1L, 42L),
            CapturedSqlSource.JPA_REPOSITORY_DERIVED);

    @Test
    void inMemoryArchiveStoresAndRetrievesEndpoint(@TempDir Path tmp) {
        GraphArchive a = new GraphArchive(tmp);
        a.addEndpoint(sampleEndpoint);

        assertThat(a.endpoints()).contains(sampleEndpoint);
        assertThat(a.findEndpoint("POST:/api/orders")).contains(sampleEndpoint);
    }

    @Test
    void inMemoryArchiveStoresAndRetrievesCapturedSql(@TempDir Path tmp) {
        GraphArchive a = new GraphArchive(tmp);
        a.addCapturedSql(sampleSql);

        assertThat(a.capturedSqlByPath("path-1")).containsExactly(sampleSql);
        assertThat(a.capturedSqlByPath("nonexistent")).isEmpty();
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path tmp) throws Exception {
        GraphArchive original = new GraphArchive(tmp);
        original.addEndpoint(sampleEndpoint);
        original.addCapturedSql(sampleSql);
        original.save();

        GraphArchive loaded = GraphArchive.load(tmp);

        assertThat(loaded.endpoints()).contains(sampleEndpoint);
        assertThat(loaded.capturedSqlByPath("path-1")).hasSize(1);
        assertThat(loaded.capturedSqlByPath("path-1").get(0).rawSql())
                .isEqualTo(sampleSql.rawSql());
    }

    @Test
    void loadFromEmptyDirReturnsEmptyArchive(@TempDir Path tmp) throws Exception {
        GraphArchive a = GraphArchive.load(tmp);

        assertThat(a.endpoints()).isEmpty();
        assertThat(a.capturedSqlByPath("any")).isEmpty();
    }

    @Test
    void saveCreatesExpectedFiles(@TempDir Path tmp) throws Exception {
        GraphArchive a = new GraphArchive(tmp);
        a.addEndpoint(sampleEndpoint);
        a.addCapturedSql(sampleSql);
        a.save();

        assertThat(tmp.resolve("endpoints.json").toFile()).exists();
        assertThat(tmp.resolve("captured_sql.json").toFile()).exists();
    }
}
