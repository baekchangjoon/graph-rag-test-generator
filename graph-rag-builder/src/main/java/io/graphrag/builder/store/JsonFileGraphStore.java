package io.graphrag.builder.store;

import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class JsonFileGraphStore implements GraphStore {

    private final Path dir;

    public JsonFileGraphStore(Path dir) {
        this.dir = dir;
    }

    private Path file() {
        return dir.resolve("graph.json");
    }

    @Override
    public void save(GraphAsset asset) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(), Json.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(asset));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save graph to " + file(), e);
        }
    }

    @Override
    public GraphAsset load() {
        try {
            return Json.mapper().readValue(Files.readString(file()), GraphAsset.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load graph from " + file(), e);
        }
    }
}
