package io.graphrag.generator.client;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.TableSchema;

import java.util.List;

/** 도구 1 조회 추상화. Phase 0: 파일. Phase 1+: HTTP query API 구현 추가. */
public interface GraphRagClient {

    Endpoint endpoint(String id);

    ExploredPath path(String id);

    List<ExploredPath> pathsForEndpoint(String endpointId);

    List<CapturedSql> sqlForPath(String pathId);

    List<io.graphrag.model.CapturedHttpCall> httpCallsForPath(String pathId);

    boolean hasWsEndpoint(String id);

    io.graphrag.model.WsEndpoint wsEndpoint(String id);

    List<io.graphrag.model.WsExchange> wsExchangesFor(String wsEndpointId);

    io.graphrag.model.WsExchange wsExchange(String exchangeId);

    List<TableSchema> tables();
}
