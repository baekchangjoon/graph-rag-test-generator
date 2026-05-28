package io.graphrag.orchestrator;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Static config for one orchestration run. Constructed by {@link Orchestrator}
 * from CLI flags and passed unchanged to every {@link IterationRunner}.
 */
public record OrchestratorConfig(
        Path sutSource,
        String project,
        String testPackage,
        Path scoutConfigTemplate,
        String scoutBaseUrl,
        Path outDir,
        double coverageTarget,
        int maxIterations) {

    public OrchestratorConfig {
        Objects.requireNonNull(sutSource, "--sut-source");
        Objects.requireNonNull(project, "--project");
        Objects.requireNonNull(testPackage, "--test-package");
        Objects.requireNonNull(scoutConfigTemplate, "--scout-config-template");
        Objects.requireNonNull(scoutBaseUrl, "--scout-base-url");
        Objects.requireNonNull(outDir, "--out");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("--max-iterations must be ≥ 1");
        }
        if (coverageTarget < 0 || coverageTarget > 1) {
            throw new IllegalArgumentException("--coverage-target must be in [0,1]");
        }
    }
}
