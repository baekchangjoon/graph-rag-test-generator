package io.graphrag.builder.explore;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.Endpoint;
import io.graphrag.model.TableSchema;

import java.util.List;

/** 탐색 대상 + 실행 핸들. docs/05 SPI의 EndpointTarget에 invoker를 포함시킨 형태. */
public record EndpointTarget(
        Endpoint endpoint,
        BodyShape shape,
        List<TableSchema> tables,
        EndpointInvoker invoker,
        List<String> literalCandidates) {

    public EndpointTarget(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                          EndpointInvoker invoker) {
        this(endpoint, shape, tables, invoker, List.of());
    }
}
