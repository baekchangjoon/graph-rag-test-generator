package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.model.Endpoint;
import io.graphrag.model.TableSchema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 탐색 대상 + 실행 핸들. docs/05 SPI의 EndpointTarget에 invoker를 포함시킨 형태. */
public record EndpointTarget(
        Endpoint endpoint,
        ObjectNode baseInput,
        List<BodyShape.BodyField> mutableFields,
        List<TableSchema> tables,
        EndpointInvoker invoker,
        List<String> literalCandidates,
        Map<String, List<FieldConstraint>> fieldConstraints,
        Map<String, Set<Long>> conditionBounds,
        Map<String, Set<String>> stringCandidates,
        Map<String, List<String>> enumConstants,
        List<ConstraintExtractor.Conjunction> conjunctions,
        List<Map<String, Long>> interFieldTuples) {

    public EndpointTarget(Endpoint endpoint, ObjectNode baseInput,
                          List<BodyShape.BodyField> mutableFields,
                          List<TableSchema> tables, EndpointInvoker invoker) {
        this(endpoint, baseInput, mutableFields, tables, invoker,
                List.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    public EndpointTarget(Endpoint endpoint, ObjectNode baseInput,
                          List<BodyShape.BodyField> mutableFields,
                          List<TableSchema> tables, EndpointInvoker invoker,
                          List<String> literalCandidates) {
        this(endpoint, baseInput, mutableFields, tables, invoker,
                literalCandidates, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }
}
