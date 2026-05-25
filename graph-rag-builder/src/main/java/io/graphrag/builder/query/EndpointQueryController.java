package io.graphrag.builder.query;

import io.graphrag.builder.persistence.GraphArchive;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 0의 단순 query API.
 *
 * <p>Phase 1+에서 path matching, branch 정보, vector search 등 확장.
 */
@RestController
public class EndpointQueryController {

    private final GraphArchive archive;

    public EndpointQueryController(GraphArchive archive) {
        this.archive = archive;
    }

    @GetMapping("/endpoints")
    public List<Endpoint> endpoints() {
        return archive.endpoints();
    }

    // id는 "METHOD:/api/path"처럼 슬래시 포함 가능 → 정규식 허용.
    @GetMapping("/endpoints/{id:.+}")
    public ResponseEntity<Endpoint> endpoint(@PathVariable String id) {
        return archive.findEndpoint(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/paths/{pathId}/captured-sql")
    public List<CapturedSql> capturedSqlForPath(@PathVariable String pathId) {
        return archive.capturedSqlByPath(pathId);
    }

    @GetMapping("/version")
    public VersionInfo version() {
        return new VersionInfo("0.1.0-SNAPSHOT", 1);
    }

    public record VersionInfo(String version, int schemaVersion) {}
}
