package io.graphrag.model;

import java.util.List;

public record GenerationResult(
        List<GeneratedFile> files,
        List<String> warnings,
        ParallelSafetyReport parallelSafety) {
}
