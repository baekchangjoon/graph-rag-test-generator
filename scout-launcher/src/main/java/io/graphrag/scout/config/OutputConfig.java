package io.graphrag.scout.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Where the SUT-side capture writes archive files.
 *
 * <p>The launcher passes {@code -Dgraphrag.archive.output.dir=<archiveDir>} to the SUT JVM.
 * SUT-side wiring (e.g. {@code JdbcAgentBaggageBridge}'s shutdown hook) reads this property
 * and writes per-path archive subdirectories under it.
 *
 * @param preserveFiles file names that {@code PipelineRunner.prepareOutputDir()} skips
 *                      when {@code clearBeforeRun} is true, and that
 *                      {@code ScoutMetadataWriter} refuses to overwrite. Lets a
 *                      pre-existing Stage 1 {@code paths.json} / {@code endpoints.json}
 *                      survive an end-to-end scout run.
 * @param strictMode    when true, any scout step whose live status differs from its
 *                      configured {@code expected-status} causes its per-path archive
 *                      subdirectory to be moved into {@code <archive-dir>/quarantine/}
 *                      after the run, instead of being silently kept (R3 mitigation).
 */
public record OutputConfig(
    @JsonProperty("archive-dir")      String archiveDir,
    @JsonProperty("clear-before-run") Boolean clearBeforeRun,
    @JsonProperty("project")          String project,
    @JsonProperty("preserve-files")   List<String> preserveFiles,
    @JsonProperty("strict-mode")      Boolean strictMode
) {
    public OutputConfig {
        Objects.requireNonNull(archiveDir, "output.archive-dir required");
        if (clearBeforeRun == null) clearBeforeRun = false;
        if (project == null || project.isBlank()) project = "scout";
        preserveFiles = List.copyOf(Objects.requireNonNullElse(preserveFiles, List.of()));
        if (strictMode == null) strictMode = false;
    }

    /** Back-compat 3-arg constructor for callers that don't care about T3 features. */
    public OutputConfig(String archiveDir, Boolean clearBeforeRun, String project) {
        this(archiveDir, clearBeforeRun, project, List.of(), false);
    }
}
