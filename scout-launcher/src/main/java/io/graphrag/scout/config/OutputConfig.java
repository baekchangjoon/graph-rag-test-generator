package io.graphrag.scout.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Where the SUT-side capture writes archive files.
 *
 * <p>The launcher passes {@code -Dgraphrag.archive.output.dir=<archiveDir>} to the SUT JVM.
 * SUT-side wiring (e.g. {@code JdbcAgentBaggageBridge}'s shutdown hook) reads this property
 * and writes per-path archive subdirectories under it.
 */
public record OutputConfig(
    @JsonProperty("archive-dir")      String archiveDir,
    @JsonProperty("clear-before-run") Boolean clearBeforeRun,
    @JsonProperty("project")          String project
) {
    public OutputConfig {
        Objects.requireNonNull(archiveDir, "output.archive-dir required");
        if (clearBeforeRun == null) clearBeforeRun = false;
        if (project == null || project.isBlank()) project = "scout";
    }
}
