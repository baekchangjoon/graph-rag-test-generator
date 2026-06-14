package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.Endpoint;
import io.graphrag.model.TableSchema;

import java.util.List;

/** 탐색 대상 + 실행 핸들. docs/05 SPI의 EndpointTarget에 invoker를 포함시킨 형태. */
public record EndpointTarget(
        Endpoint endpoint,
        ObjectNode baseInput,
        List<BodyShape.BodyField> mutableFields,
        List<TableSchema> tables,
        EndpointInvoker invoker,
        List<String> literalCandidates) {

    public EndpointTarget(Endpoint endpoint, ObjectNode baseInput,
                          List<BodyShape.BodyField> mutableFields,
                          List<TableSchema> tables, EndpointInvoker invoker) {
        this(endpoint, baseInput, mutableFields, tables, invoker, List.of());
    }
}
