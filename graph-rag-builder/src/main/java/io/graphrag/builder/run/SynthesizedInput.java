package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 결정적으로 합성된 요청 body + 사전 seed row. body는 object(스칼라 필드) 또는 array(컬렉션 happy). */
public record SynthesizedInput(JsonNode body, List<SeedRow> seeds) {

    public record SeedRow(String table, List<String> columns, List<Object> values) {
    }
}
