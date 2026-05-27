package io.graphrag.scout.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/** External dependencies the SUT needs (DB, Redis, Kafka, ...). Optional. */
public record DependenciesConfig(
    @JsonProperty("docker-compose") DockerCompose dockerCompose
) {
    public record DockerCompose(
        @JsonProperty("file")                   String file,
        @JsonProperty("services")               List<String> services,
        @JsonProperty("wait-for-healthy")       Boolean waitForHealthy,
        @JsonProperty("teardown-on-exit")       Boolean teardownOnExit,
        @JsonProperty("health-timeout-seconds") Integer healthTimeoutSeconds
    ) {
        public DockerCompose {
            Objects.requireNonNull(file, "docker-compose.file is required");
            services = List.copyOf(Objects.requireNonNullElse(services, List.of()));
            if (waitForHealthy == null) waitForHealthy = true;
            if (teardownOnExit == null) teardownOnExit = true;
            if (healthTimeoutSeconds == null || healthTimeoutSeconds <= 0) healthTimeoutSeconds = 120;
        }
    }
}
