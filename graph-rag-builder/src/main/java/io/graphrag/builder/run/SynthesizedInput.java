package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 결정적으로 합성된 요청 body + 사전 seed row. */
public record SynthesizedInput(ObjectNode body, List<SeedRow> seeds) {

    public record SeedRow(String table, List<String> columns, List<Object> values) {
    }
}
