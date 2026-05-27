package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.ScoutConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * End-to-end scout pipeline: deps → SUT → HTTP scout → SUT shutdown → deps down.
 * Each stage's failure short-circuits the rest; finally blocks always run for cleanup.
 */
public final class PipelineRunner {

    private final ScoutConfig cfg;

    public PipelineRunner(ScoutConfig cfg) { this.cfg = cfg; }

    public void run() throws Exception {
        prepareOutputDir();

        java.util.List<ScoutResult> results;
        try (DockerComposeOrchestrator deps = new DockerComposeOrchestrator(
                cfg.dependencies() == null ? null : cfg.dependencies().dockerCompose())) {
            deps.start();

            try (SutProcessOrchestrator sut = new SutProcessOrchestrator(cfg.sut(), cfg.output().archiveDir())) {
                sut.start();

                HttpScout scout = new HttpScout(cfg.scout());
                results = scout.run();

                System.out.println("[scout] all scout steps issued; shutting down SUT to flush archive");
            } // SUT shutdown — archive captured_sql.json written by SUT-side wiring shutdown hook

            Path archiveRoot = Paths.get(cfg.output().archiveDir());
            new ScoutMetadataWriter(archiveRoot, cfg.output().project()).write(results);
            System.out.println("[scout] archive written under " + archiveRoot.toAbsolutePath());
        } // docker compose down
    }

    private void prepareOutputDir() throws IOException {
        Path out = Paths.get(cfg.output().archiveDir());
        if (Boolean.TRUE.equals(cfg.output().clearBeforeRun()) && Files.isDirectory(out)) {
            try (var stream = Files.walk(out)) {
                stream.sorted((a, b) -> -a.toString().compareTo(b.toString()))
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
        Files.createDirectories(out);
    }
}
