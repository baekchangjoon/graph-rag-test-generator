package io.graphrag.scout.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Parses {@link ScoutConfig} from YAML on disk. */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
        .registerModule(new JavaTimeModule());

    private ConfigLoader() {}

    public static ScoutConfig load(Path yamlPath) throws IOException {
        if (!Files.isRegularFile(yamlPath)) {
            throw new IllegalArgumentException("config file not found: " + yamlPath);
        }
        try (var in = Files.newInputStream(yamlPath)) {
            return MAPPER.readValue(in, ScoutConfig.class);
        }
    }
}
