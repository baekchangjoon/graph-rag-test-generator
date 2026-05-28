package io.graphrag.builder.staticanalysis.cli;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Parsed CLI arguments for {@link StaticAnalysisCli}.
 *
 * @param sutSource           required — root of the SUT's {@code src/main/java}
 * @param project             required — project identifier stamped into Endpoints
 * @param out                 required — output directory for the three JSON files
 * @param codeVersion         optional, defaults to {@code "static-1"}
 * @param maxPathsPerEndpoint optional, defaults to {@code 10}
 * @param excludePaths        optional, defaults to empty set
 */
public record StaticAnalysisOptions(
        Path sutSource,
        String project,
        Path out,
        String codeVersion,
        int maxPathsPerEndpoint,
        Set<String> excludePaths) {

    public StaticAnalysisOptions {
        Objects.requireNonNull(sutSource, "sutSource");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(codeVersion, "codeVersion");
        if (maxPathsPerEndpoint < 0)
            throw new IllegalArgumentException("maxPathsPerEndpoint must be >= 0");
        excludePaths = Set.copyOf(Objects.requireNonNull(excludePaths, "excludePaths"));
    }
}
