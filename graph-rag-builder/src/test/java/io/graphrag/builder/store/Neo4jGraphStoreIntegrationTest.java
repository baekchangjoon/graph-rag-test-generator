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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.Neo4jContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Neo4j 통합 테스트. Docker가 사용 가능한 환경에서만 실행.
 *
 * <p>실행: {@code GRAPH_RAG_NEO4J_TEST=1 ./gradlew :graph-rag-builder:test --tests "*Neo4jGraphStore*"}
 */
@EnabledIfEnvironmentVariable(named = "GRAPH_RAG_NEO4J_TEST", matches = "1")
class Neo4jGraphStoreIntegrationTest {

    private static Neo4jContainer<?> neo4j;
    private static Neo4jGraphStore store;

    @BeforeAll
    static void start() {
        neo4j = new Neo4jContainer<>("neo4j:5-community")
                .withAdminPassword("test-password");
        neo4j.start();
        store = new Neo4jGraphStore(
                neo4j.getBoltUrl(), "neo4j", "test-password");
    }

    @AfterAll
    static void stop() {
        if (store != null) store.close();
        if (neo4j != null) neo4j.stop();
    }

    @Test
    void saveAndQueryEndpoint() {
        Endpoint ep = new Endpoint("POST:/api/x", HttpMethod.POST, "/api/x",
                "demo", "C", "m", false, List.of());
        store.saveEndpoint(ep);

        assertThat(store.findEndpoint(ep.id())).isPresent()
                .get().satisfies(found -> assertThat(found.path()).isEqualTo("/api/x"));
        assertThat(store.allEndpoints()).anyMatch(e -> e.id().equals(ep.id()));
    }

    @Test
    void savePathLinksToEndpoint() {
        Endpoint ep = new Endpoint("POST:/api/y", HttpMethod.POST, "/api/y",
                "demo", "C", "m", false, List.of());
        store.saveEndpoint(ep);
        ExploredPath path = new ExploredPath("py", ep.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of()),
                null, List.of(), 201, null, "sig", "v1");
        store.savePath(path);

        assertThat(store.pathsByEndpoint(ep.id()))
                .anyMatch(p -> p.id().equals("py"));
        assertThat(store.findPath("py")).isPresent();
    }

    @Test
    void saveCapturedSqlAndHttpLinkedToPath() {
        Endpoint ep = new Endpoint("POST:/api/z", HttpMethod.POST, "/api/z",
                "demo", "C", "m", false, List.of());
        store.saveEndpoint(ep);
        store.savePath(new ExploredPath("pz", ep.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of()),
                null, List.of(), 201, null, "sig", "v1"));
        store.saveCapturedSql(new CapturedSql("sql-z", "pz", CapturedSqlType.INSERT,
                "INSERT INTO orders VALUES (?)",
                List.of(new Binding(0, 1, BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_ENTITYMANAGER,
                new SourceLocation("X", "y", 1), List.of("orders"), List.of()));
        store.saveCapturedHttpCall(new CapturedHttpCall("http-z", "pz", "GET",
                "/inv", "/inv", List.of(), Map.of(), null, List.of(),
                200, "{}", List.of(), HttpClientType.OTHER, "ext"));

        assertThat(store.capturedSqlByPath("pz")).hasSize(1);
        assertThat(store.capturedHttpByPath("pz")).hasSize(1);
    }
}
