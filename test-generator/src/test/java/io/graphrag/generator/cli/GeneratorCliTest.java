package io.graphrag.generator.cli;

import io.graphrag.model.AuthMode;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorCliTest {

    private static final Path GRAPH = Path.of("src/test/resources/fixture-graph");

    @TempDir
    Path tempDir;

    // ─── REQ-005: CLI overwrite-warning helper ───────────────────────────────

    /** helper: absent file → returns false (no warn), file is written. */
    @Test
    void writeFile_absentFile_writesAndReturnsFalse() throws Exception {
        Path target = tempDir.resolve("junit-platform.properties");
        Logger log = LoggerFactory.getLogger(GeneratorCliTest.class);

        boolean overwrote = GeneratorCli.writeFile(target, "content=A\n", log);

        assertThat(overwrote).isFalse();
        assertThat(Files.readString(target)).isEqualTo("content=A\n");
    }

    /** helper: existing file with IDENTICAL content → returns false (no warn), file unchanged. */
    @Test
    void writeFile_identicalContent_returnsFalse() throws Exception {
        Path target = tempDir.resolve("junit-platform.properties");
        Files.writeString(target, "content=A\n");
        Logger log = LoggerFactory.getLogger(GeneratorCliTest.class);

        boolean overwrote = GeneratorCli.writeFile(target, "content=A\n", log);

        assertThat(overwrote).isFalse();
        assertThat(Files.readString(target)).isEqualTo("content=A\n");
    }

    /** helper: existing file with DIFFERENT content → returns true (warn issued), file overwritten. */
    @Test
    void writeFile_existingDifferentContent_returnsTrueAndOverwrites() throws Exception {
        Path target = tempDir.resolve("junit-platform.properties");
        Files.writeString(target, "junit.jupiter.execution.parallel.enabled=false\n"); // custom, different
        Logger log = LoggerFactory.getLogger(GeneratorCliTest.class);

        boolean overwrote = GeneratorCli.writeFile(target, "junit.jupiter.execution.parallel.enabled=true\n", log);

        assertThat(overwrote).isTrue();
        assertThat(Files.readString(target)).isEqualTo("junit.jupiter.execution.parallel.enabled=true\n");
    }

    // ─── REQ-005: end-to-end via CLI main ────────────────────────────────────

    /**
     * When an existing junit-platform.properties differs from what we emit,
     * the CLI must overwrite it with the generated content (REQ-005).
     */
    @Test
    void overwritesExistingDifferentPropertiesWithEmittedContent() throws Exception {
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        Path req = tempDir.resolve("req.json");
        Files.writeString(req, Json.mapper().writeValueAsString(
                new GenerationRequest("post-api-orders", "post-api-orders-happy",
                        "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED)));
        Path props = out.resolve("junit-platform.properties");
        Files.writeString(props, "junit.jupiter.execution.parallel.enabled=false\n"); // pre-existing, different

        GeneratorCli.main(new String[]{"generate",
                "--request", req.toString(), "--graph", GRAPH.toString(), "--out", out.toString()});

        assertThat(Files.readString(props)).contains("config.strategy=dynamic");
    }
}
