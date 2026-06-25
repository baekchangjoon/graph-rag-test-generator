package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageReportWiringTest {

    @Test
    void noExecDirSkips(@TempDir Path out) {
        assertThat(BuilderCli.shouldWriteCoverageReport(out)).isFalse();
    }

    @Test
    void emptyExecDirSkips(@TempDir Path out) throws Exception {
        Files.createDirectories(out.resolve("work/pjacoco-exec"));
        assertThat(BuilderCli.shouldWriteCoverageReport(out)).isFalse();
    }

    @Test
    void execPresentTriggers(@TempDir Path out) throws Exception {
        Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(out.resolve("work/pjacoco-exec/a.exec"), "");
        assertThat(BuilderCli.shouldWriteCoverageReport(out)).isTrue();
    }
}
