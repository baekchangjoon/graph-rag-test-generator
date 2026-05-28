package io.graphrag.orchestrator;

import io.graphrag.feedback.MissingBranch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wiring fix from spec §2.2: every iteration's stage2-config.yml must end up
 * with output.archive-dir pointing at THIS iter's stage3-archive, regardless of what
 * the template said. Without this, the real Shell runner can't compose with the iter-
 * scoped reader contract.
 */
class IterationRunnerArchiveDirTest {

    @Test
    void afterStage2_outputArchiveDir_pointsAtIterStage3Archive(@TempDir Path tmp)
            throws Exception {
        // Build a minimal SUT source tree with one endpoint so Stage 1 produces non-empty
        // paths/endpoints; this is the same fixture shape OrchestratorE2eTest uses.
        Path sutSrc = tmp.resolve("sut/src/main/java/demo");
        Files.createDirectories(sutSrc);
        Files.writeString(sutSrc.resolve("Demo.java"), """
                package demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                class Demo {
                    @GetMapping("/ping")
                    public String ping() { return "pong"; }
                }
                """);

        // Template ships an explicit (wrong-for-this-iter) archive-dir.
        Path template = tmp.resolve("template.yml");
        Files.writeString(template, """
                sut: { jar: /dev/null }
                output:
                  archive-dir: /tmp/should-be-overwritten
                  project: demo
                """);

        Path outDir = tmp.resolve("out");

        OrchestratorConfig cfg = new OrchestratorConfig(
                tmp.resolve("sut/src/main/java"),
                "demo",
                "demo.tests",
                template,
                "http://localhost:0",
                outDir,
                0.70,
                1);

        RecordingExternal external = new RecordingExternal();
        IterationRunner runner = new IterationRunner(cfg, external,
                new PrintStream(new ByteArrayOutputStream()));

        // runOne may throw at Stage 6 (JaCoCoXmlParser rejects the minimal XML), but
        // stage2-config.yml is written during Stage 2 — before Stage 3 is even called.
        // We catch any exception so the assertion below always fires.
        try {
            runner.runOne(1, List.of(), List.of(), Set.of());
        } catch (Exception ignored) {
            // archive-dir is what we care about; later stages may legitimately fail
            // in this isolated fixture.
        }

        Path stage2Config = outDir.resolve("iter-1/stage2-config.yml");
        assertThat(stage2Config).exists();

        // Parse archive-dir from the YAML by finding the "archive-dir:" line.
        // Avoids a test-scoped jackson-dataformat-yaml dependency.
        String archiveDir = Files.readAllLines(stage2Config).stream()
                .filter(line -> line.contains("archive-dir:"))
                .map(line -> line.substring(line.indexOf("archive-dir:") + "archive-dir:".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("archive-dir key not found in " + stage2Config));

        assertThat(archiveDir)
                .isEqualTo(outDir.resolve("iter-1/stage3-archive").toString());
    }

    /** Records nothing; just satisfies the Stage 3/4/5 contracts so runOne completes. */
    private static final class RecordingExternal implements ExternalStageRunner {
        @Override public void runScout(Path configYaml, Path archiveDir) throws IOException {
            Files.createDirectories(archiveDir);
        }
        @Override public void runTestGenerator(Path archiveDir, List<String> endpointIds,
                                               String testPackage, Path outDir) throws IOException {
            Files.createDirectories(outDir);
        }
        @Override public void runTestsAndJacoco(Path generatedTestsDir, Path jacocoOut)
                throws IOException {
            Files.createDirectories(jacocoOut.getParent());
            // Smallest JaCoCo XML that JaCoCoXmlParser will accept (no branches → ratio 0).
            // No DOCTYPE — JaCoCoXmlParser disallows DOCTYPE declarations per XXE hardening.
            Files.writeString(jacocoOut, """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <report name="demo"></report>
                    """);
        }
    }
}
