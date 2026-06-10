package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record ExplorationResult(List<ExploredInput> inputs) {

    public record ExploredInput(JsonNode body, InvocationOutcome outcome) {
    }
}
