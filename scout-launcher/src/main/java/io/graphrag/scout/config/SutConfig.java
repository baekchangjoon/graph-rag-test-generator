package io.graphrag.scout.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How to launch the SUT JVM.
 *
 * <p>Either {@code jar} (Spring Boot fat jar — launched with {@code java -jar})
 * OR ({@code main-class} + {@code classpath}) (plain JVM launch). One of the two
 * variants must be set; if both, {@code jar} wins.
 */
public record SutConfig(
    @JsonProperty("jar")              String jar,
    @JsonProperty("main-class")       String mainClass,
    @JsonProperty("classpath")        List<String> classpath,
    @JsonProperty("args")             List<String> args,
    @JsonProperty("jvm-args")         List<String> jvmArgs,
    @JsonProperty("agents")           List<String> agents,
    @JsonProperty("boot-classpath")   List<String> bootClasspath,
    @JsonProperty("system-properties") Map<String, String> systemProperties,
    @JsonProperty("health-check")     HealthCheck healthCheck
) {
    public SutConfig {
        if (jar == null && mainClass == null) {
            throw new IllegalArgumentException("sut: one of {jar, main-class} required");
        }
        classpath = List.copyOf(Objects.requireNonNullElse(classpath, List.of()));
        args = List.copyOf(Objects.requireNonNullElse(args, List.of()));
        jvmArgs = List.copyOf(Objects.requireNonNullElse(jvmArgs, List.of()));
        agents = List.copyOf(Objects.requireNonNullElse(agents, List.of()));
        bootClasspath = List.copyOf(Objects.requireNonNullElse(bootClasspath, List.of()));
        systemProperties = Map.copyOf(Objects.requireNonNullElse(systemProperties, Map.of()));
        if (healthCheck == null) healthCheck = HealthCheck.DEFAULT;
    }

    public record HealthCheck(
        @JsonProperty("url")              String url,
        @JsonProperty("timeout-seconds")  Integer timeoutSeconds,
        @JsonProperty("interval-millis")  Integer intervalMillis
    ) {
        public static final HealthCheck DEFAULT = new HealthCheck(null, null, null);

        public HealthCheck {
            if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 60;
            if (intervalMillis == null || intervalMillis <= 0) intervalMillis = 1000;
        }
    }
}
