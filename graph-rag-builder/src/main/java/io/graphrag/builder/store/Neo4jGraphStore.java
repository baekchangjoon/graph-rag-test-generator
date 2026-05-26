package io.graphrag.builder.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 6 Neo4j 백엔드.
 *
 * <p>스키마:
 * <pre>
 * (:Endpoint {id, method, path, project, ...})
 * (:Path {id, endpoint_id, exit_status, ...})
 * (:Sql {id, path_id, raw_sql, type, source, ...})
 * (:HttpCall {id, path_id, url_concrete, method, response_status, ...})
 *
 * (:Endpoint)-[:HAS_PATH]->(:Path)
 * (:Path)-[:EXECUTED]->(:Sql)
 * (:Path)-[:INVOKED]->(:HttpCall)
 * </pre>
 *
 * <p>객체의 detailed 필드는 JSON 문자열로 {@code payload} 속성에 저장 — 스키마 진화 대응.
 */
public final class Neo4jGraphStore implements GraphStore {

    private static final ObjectMapper MAPPER = JsonMappers.standard();

    private final Driver driver;

    public Neo4jGraphStore(String uri, String user, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    public Neo4jGraphStore(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    @Override
    public void saveEndpoint(Endpoint endpoint) {
        String payload = serialize(endpoint);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (e:Endpoint {id: $id}) "
                                + "SET e.method = $method, e.path = $path, "
                                + "    e.project = $project, e.payload = $payload",
                        Values.parameters(
                                "id", endpoint.id(),
                                "method", endpoint.method().name(),
                                "path", endpoint.path(),
                                "project", endpoint.project(),
                                "payload", payload));
                return null;
            });
        }
    }

    @Override
    public Optional<Endpoint> findEndpoint(String id) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record r = tx.run("MATCH (e:Endpoint {id: $id}) RETURN e.payload AS payload",
                                Values.parameters("id", id))
                        .list().stream().findFirst().orElse(null);
                if (r == null) return Optional.<Endpoint>empty();
                return Optional.of(deserialize(r.get("payload").asString(), Endpoint.class));
            });
        }
    }

    @Override
    public List<Endpoint> allEndpoints() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Endpoint> out = new ArrayList<>();
                tx.run("MATCH (e:Endpoint) RETURN e.payload AS payload")
                        .list().forEach(r ->
                                out.add(deserialize(r.get("payload").asString(), Endpoint.class)));
                return out;
            });
        }
    }

    @Override
    public void savePath(ExploredPath path) {
        String payload = serialize(path);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (p:Path {id: $id}) "
                                + "SET p.endpoint_id = $endpointId, p.exit_status = $exitStatus, "
                                + "    p.payload = $payload "
                                + "WITH p "
                                + "MATCH (e:Endpoint {id: $endpointId}) "
                                + "MERGE (e)-[:HAS_PATH]->(p)",
                        Values.parameters(
                                "id", path.id(),
                                "endpointId", path.endpointId(),
                                "exitStatus", path.exitStatus(),
                                "payload", payload));
                return null;
            });
        }
    }

    @Override
    public Optional<ExploredPath> findPath(String id) {
        return queryOne("MATCH (p:Path {id: $id}) RETURN p.payload AS payload",
                Map.of("id", id), ExploredPath.class);
    }

    @Override
    public List<ExploredPath> pathsByEndpoint(String endpointId) {
        return queryMany("MATCH (:Endpoint {id: $id})-[:HAS_PATH]->(p:Path) RETURN p.payload AS payload",
                Map.of("id", endpointId), ExploredPath.class);
    }

    @Override
    public void saveCapturedSql(CapturedSql sql) {
        String payload = serialize(sql);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (s:Sql {id: $id}) "
                                + "SET s.path_id = $pathId, s.type = $type, s.payload = $payload "
                                + "WITH s "
                                + "MATCH (p:Path {id: $pathId}) "
                                + "MERGE (p)-[:EXECUTED]->(s)",
                        Values.parameters(
                                "id", sql.id(),
                                "pathId", sql.pathId(),
                                "type", sql.type().name(),
                                "payload", payload));
                return null;
            });
        }
    }

    @Override
    public List<CapturedSql> capturedSqlByPath(String pathId) {
        return queryMany("MATCH (:Path {id: $id})-[:EXECUTED]->(s:Sql) RETURN s.payload AS payload",
                Map.of("id", pathId), CapturedSql.class);
    }

    @Override
    public void saveCapturedHttpCall(CapturedHttpCall call) {
        String payload = serialize(call);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (h:HttpCall {id: $id}) "
                                + "SET h.path_id = $pathId, h.method = $method, h.payload = $payload "
                                + "WITH h "
                                + "MATCH (p:Path {id: $pathId}) "
                                + "MERGE (p)-[:INVOKED]->(h)",
                        Values.parameters(
                                "id", call.id(),
                                "pathId", call.pathId(),
                                "method", call.method(),
                                "payload", payload));
                return null;
            });
        }
    }

    @Override
    public List<CapturedHttpCall> capturedHttpByPath(String pathId) {
        return queryMany("MATCH (:Path {id: $id})-[:INVOKED]->(h:HttpCall) RETURN h.payload AS payload",
                Map.of("id", pathId), CapturedHttpCall.class);
    }

    private <T> Optional<T> queryOne(String cypher, Map<String, Object> params, Class<T> type) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record r = tx.run(cypher, paramsValue(params)).list().stream().findFirst().orElse(null);
                if (r == null) return Optional.<T>empty();
                return Optional.of(deserialize(r.get("payload").asString(), type));
            });
        }
    }

    private <T> List<T> queryMany(String cypher, Map<String, Object> params, Class<T> type) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<T> out = new ArrayList<>();
                tx.run(cypher, paramsValue(params)).list().forEach(r ->
                        out.add(deserialize(r.get("payload").asString(), type)));
                return out;
            });
        }
    }

    private static Value paramsValue(Map<String, Object> params) {
        Object[] flat = new Object[params.size() * 2];
        int i = 0;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            flat[i++] = entry.getKey();
            flat[i++] = entry.getValue();
        }
        return Values.parameters(flat);
    }

    private static String serialize(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }

    private static <T> T deserialize(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }

    @Override
    public void close() {
        driver.close();
    }
}
