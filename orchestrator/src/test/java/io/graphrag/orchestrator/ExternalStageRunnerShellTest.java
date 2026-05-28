package io.graphrag.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalStageRunnerShellTest {

    @Test
    void runTestsAndJacoco_appendsTestsDirAndJacocoOutAsPositionalArgs(@TempDir Path tmp)
            throws IOException, InterruptedException {
        // Stub user-test command: a shell script that records argv to a sentinel
        // and touches the requested jacoco.xml so Shell's post-condition is satisfied.
        Path script = tmp.resolve("echo-args.sh");
        Path sentinel = tmp.resolve("argv.txt");
        Files.writeString(script, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%%s\\n' "$@" > "%s"
                # Stage-5 contract: second positional arg is the jacoco.xml destination.
                : > "$2"
                """.formatted(sentinel));
        Files.setPosixFilePermissions(script,
                PosixFilePermissions.fromString("rwxr-xr-x"));

        Path testsDir  = tmp.resolve("iter-1/stage4-tests");
        Path jacocoOut = tmp.resolve("iter-1/stage5-jacoco.xml");
        Files.createDirectories(testsDir);

        ExternalStageRunner.Shell shell = new ExternalStageRunner.Shell(
                tmp.resolve("unused-scout"),
                tmp.resolve("unused-tg"),
                List.of(script.toString()));
        shell.runTestsAndJacoco(testsDir, jacocoOut);

        assertThat(Files.readAllLines(sentinel))
                .containsExactly(testsDir.toString(), jacocoOut.toString());
        assertThat(Files.exists(jacocoOut)).isTrue();
    }
}
