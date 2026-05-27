package io.graphrag.scout.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Root of the scout-launcher YAML config.
 *
 * <p>One pipeline run = boot dependencies → boot SUT (with agents) → execute scout
 * HTTP steps → shutdown SUT → shutdown dependencies. The launcher does not capture
 * SQL itself; SUT-side wiring (datasource-proxy {@code BeanPostProcessor} or
 * {@code jdbc-intercept-agent}) writes a graph-rag archive under
 * {@code output.archive-dir}.
 */
public record ScoutConfig(
    @JsonProperty("sut")          SutConfig sut,
    @JsonProperty("dependencies") DependenciesConfig dependencies,
    @JsonProperty("scout")        ScoutSection scout,
    @JsonProperty("output")       OutputConfig output
) {
    public ScoutConfig {
        Objects.requireNonNull(sut, "sut section is required");
        Objects.requireNonNull(scout, "scout section is required");
        Objects.requireNonNull(output, "output section is required");
        // dependencies is optional
    }

    /** Capture section — base URL + list of HTTP steps to issue. */
    public record ScoutSection(
        @JsonProperty("base-url") String baseUrl,
        @JsonProperty("steps")    List<CaptureStep> steps
    ) {
        public ScoutSection {
            Objects.requireNonNull(baseUrl, "scout.base-url is required");
            steps = List.copyOf(Objects.requireNonNullElse(steps, List.of()));
        }
    }
}
