package io.graphrag.orchestrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Indirection over the three stages this orchestrator does NOT own end-to-end:
 * <ul>
 *   <li>Stage 3 (scout-launcher) — needs Docker and a built SUT jar; lives in
 *       another module's process space.</li>
 *   <li>Stage 4 (test-generator per endpoint) — runs as its own CLI process.</li>
 *   <li>Stage 5 (gradle/maven test + jacocoTestReport) — runs the user's build.</li>
 * </ul>
 *
 * <p>The production implementation shells out via {@link ProcessBuilder}.
 * Tests can swap in a fake that just writes the expected output files. This keeps
 * the orchestration loop unit-testable without Docker.
 */
public interface ExternalStageRunner {

    /** Run scout-launcher with {@code config.yml}; expect it to populate {@code archiveDir}. */
    void runScout(Path configYaml, Path archiveDir) throws IOException, InterruptedException;

    /**
     * Run test-generator once per endpoint id, writing under {@code outDir}.
     * The orchestrator already knows the endpoint ids from Stage 1.
     */
    void runTestGenerator(Path archiveDir, List<String> endpointIds,
                          String testPackage, Path outDir)
            throws IOException, InterruptedException;

    /**
     * Run the user's test suite against the generated tests + collect JaCoCo XML
     * to {@code jacocoOut}. Concrete impl: invoke gradle/maven on the user's
     * project, then copy the jacoco.xml into place.
     */
    void runTestsAndJacoco(Path generatedTestsDir, Path jacocoOut)
            throws IOException, InterruptedException;

    /**
     * Default implementation that shells out using the install-dist scripts shipped
     * by this repo. Concrete external commands stay configurable through env vars so
     * different CI setups can override paths.
     */
    final class Shell implements ExternalStageRunner {

        private final Path scoutLauncherBin;
        private final Path testGeneratorBin;
        private final List<String> userTestCommand;

        public Shell(Path scoutLauncherBin, Path testGeneratorBin, List<String> userTestCommand) {
            this.scoutLauncherBin = scoutLauncherBin;
            this.testGeneratorBin = testGeneratorBin;
            this.userTestCommand = List.copyOf(userTestCommand);
        }

        @Override
        public void runScout(Path configYaml, Path archiveDir)
                throws IOException, InterruptedException {
            // Archive dir comes from configYaml (output.archive-dir); the launcher creates
            // it itself. We just have to make sure the parent exists.
            Files.createDirectories(archiveDir.getParent());
            spawn(List.of(scoutLauncherBin.toString(), configYaml.toString()));
        }

        @Override
        public void runTestGenerator(Path archiveDir, List<String> endpointIds,
                                     String testPackage, Path outDir)
                throws IOException, InterruptedException {
            Files.createDirectories(outDir);
            for (String id : endpointIds) {
                spawn(List.of(
                        testGeneratorBin.toString(),
                        "--archive",   archiveDir.toString(),
                        "--endpoint",  id,
                        "--package",   testPackage,
                        "--out",       outDir.toString()));
            }
        }

        @Override
        public void runTestsAndJacoco(Path generatedTestsDir, Path jacocoOut)
                throws IOException, InterruptedException {
            // The wrapper script needs to know which iter-N to read from and where to write
            // jacoco.xml; both are per-iteration so they can't live in userTestCommand itself.
            java.util.List<String> cmd = new java.util.ArrayList<>(userTestCommand);
            cmd.add(generatedTestsDir.toString());
            cmd.add(jacocoOut.toString());
            spawn(cmd);
            if (!Files.exists(jacocoOut)) {
                throw new IOException("expected JaCoCo XML at " + jacocoOut
                        + " but the user test command did not produce it");
            }
        }

        private static void spawn(List<String> command) throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
            int code = pb.start().waitFor();
            if (code != 0) {
                throw new IOException(command.get(0) + " exited " + code);
            }
        }
    }
}
