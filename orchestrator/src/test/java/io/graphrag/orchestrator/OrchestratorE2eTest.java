package io.graphrag.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorE2eTest {

    /**
     * Full orchestration loop with fake external stages. Stages 1, 2, 6 are real
     * (in-process via PathDiscoveryStatic, ScoutStepTranslator, CoverageFeedback);
     * stages 3, 4, 5 are simulated by a fake that drops the expected output files.
     */
    @Test
    void single_iteration_target_reached_terminates_and_writes_report(@TempDir Path tmp)
            throws Exception {
        Path samples = locateResource("sample-controllers");
        Path template = copyResource("petclinic-test-template.yml", tmp.resolve("template.yml"));
        // The fake stage 5 will deposit this JaCoCo report — branch coverage 1.0 → target met.
        Path scriptedJacoco = copyResource("perfect-jacoco.xml", tmp.resolve("perfect-jacoco.xml"));

        OrchestratorConfig cfg = new OrchestratorConfig(
                samples,
                "petclinic",
                "com.example.tests",
                template,
                "http://localhost:8084",
                tmp.resolve("out"),
                /* coverageTarget */ 0.85,
                /* maxIterations */ 3);

        ExternalStageRunner fake = new FakeExternal(scriptedJacoco);
        int code = Orchestrator.runLoop(cfg, fake, new PrintStream(new ByteArrayOutputStream()));

        assertThat(code).isZero();
        // Only iter-1 should exist — target reached, loop terminated.
        assertThat(Files.exists(tmp.resolve("out/iter-1"))).isTrue();
        assertThat(Files.exists(tmp.resolve("out/iter-2"))).isFalse();
        Path report = tmp.resolve("out/final-report.md");
        assertThat(Files.exists(report)).isTrue();

        String reportText = Files.readString(report);
        assertThat(reportText)
                .contains("graph-rag iteration report")
                .contains("target_reached")
                .contains("iterations completed: 1");
    }

    @Test
    void max_iterations_cap_stops_when_no_progress_and_target_unreached(@TempDir Path tmp)
            throws Exception {
        Path samples = locateResource("sample-controllers");
        Path template = copyResource("petclinic-test-template.yml", tmp.resolve("template.yml"));
        Path mediocreJacoco = copyResource("low-jacoco.xml", tmp.resolve("low-jacoco.xml"));

        // Coverage target 0.99 is unreachable from this jacoco report. Loop should run until
        // either max-iterations OR 2-consecutive-empty terminate fires. Since the fake always
        // returns the same jacoco, newly_covered becomes empty starting iter 2 → terminate at iter 3.
        OrchestratorConfig cfg = new OrchestratorConfig(
                samples,
                "petclinic",
                "com.example.tests",
                template,
                "http://localhost:8084",
                tmp.resolve("out"),
                /* coverageTarget */ 0.99,
                /* maxIterations */ 5);

        ExternalStageRunner fake = new FakeExternal(mediocreJacoco);
        int code = Orchestrator.runLoop(cfg, fake, new PrintStream(new ByteArrayOutputStream()));

        assertThat(code).isZero();
        // iter-1 + iter-2 + iter-3 (where 2-empty rule fires), iter-4+ should NOT exist
        assertThat(Files.exists(tmp.resolve("out/iter-1"))).isTrue();
        assertThat(Files.exists(tmp.resolve("out/iter-2"))).isTrue();
        assertThat(Files.exists(tmp.resolve("out/iter-3"))).isTrue();
        assertThat(Files.exists(tmp.resolve("out/iter-4"))).isFalse();

        String report = Files.readString(tmp.resolve("out/final-report.md"));
        assertThat(report)
                .contains("two_iterations_no_progress")
                .contains("iterations completed: 3");
    }

    /**
     * Skips Stages 3 and 4 (no external scout/test-generator to invoke) and just deposits
     * the scripted JaCoCo report when Stage 5 runs. This lets the real Stages 1, 2, 6
     * exercise the full orchestration without needing Docker or a built petclinic SUT.
     */
    private static final class FakeExternal implements ExternalStageRunner {
        private final Path scriptedJacoco;
        FakeExternal(Path scriptedJacoco) { this.scriptedJacoco = scriptedJacoco; }
        @Override public void runScout(Path configYaml, Path archiveDir) throws IOException {
            // Stage 3 only needs to create the archive directory; downstream stages
            // (4, 5) don't read it in this fake.
            Files.createDirectories(archiveDir);
        }
        @Override public void runTestGenerator(Path archiveDir, List<String> endpointIds,
                                               String testPackage, Path outDir) throws IOException {
            Files.createDirectories(outDir);
        }
        @Override public void runTestsAndJacoco(Path generatedTestsDir, Path jacocoOut)
                throws IOException {
            Files.createDirectories(jacocoOut.getParent());
            Files.copy(scriptedJacoco, jacocoOut, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path locateResource(String name) {
        try {
            return Path.of(OrchestratorE2eTest.class.getClassLoader().getResource(name).toURI());
        } catch (Exception ex) {
            throw new IllegalStateException("missing test resource: " + name, ex);
        }
    }

    private static Path copyResource(String name, Path dst) throws IOException {
        try (var in = OrchestratorE2eTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("missing resource: " + name);
            Files.write(dst, in.readAllBytes());
        }
        return dst;
    }
}
