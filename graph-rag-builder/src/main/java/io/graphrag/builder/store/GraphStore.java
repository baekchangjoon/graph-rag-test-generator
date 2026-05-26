package io.graphrag.builder.store;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;

import java.util.List;
import java.util.Optional;

/**
 * Phase 6 — 그래프 저장소 추상화.
 *
 * <p>file 기반은 Phase 0-5에서 사용. Neo4j 등 분산 저장소는 Phase 6+.
 * 본 인터페이스는 둘을 swap 가능하게 한다.
 */
public interface GraphStore extends AutoCloseable {

    void saveEndpoint(Endpoint endpoint);

    Optional<Endpoint> findEndpoint(String id);

    List<Endpoint> allEndpoints();

    void savePath(ExploredPath path);

    Optional<ExploredPath> findPath(String id);

    List<ExploredPath> pathsByEndpoint(String endpointId);

    void saveCapturedSql(CapturedSql sql);

    List<CapturedSql> capturedSqlByPath(String pathId);

    void saveCapturedHttpCall(CapturedHttpCall call);

    List<CapturedHttpCall> capturedHttpByPath(String pathId);

    @Override
    default void close() {}
}
