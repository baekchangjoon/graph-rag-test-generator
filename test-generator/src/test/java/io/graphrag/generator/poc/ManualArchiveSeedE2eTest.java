package io.graphrag.generator.poc;

import io.graphrag.generator.archive.ArchiveReader;
import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.TestSynthesizer;
import io.graphrag.generator.verify.CompileResult;
import io.graphrag.generator.verify.JavaSourceCompiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 PoC acceptance test — verifies that an archive directory whose paths.json and
 * endpoints.json were authored by hand (no scout-launcher involvement) produces
 * compilable RestAssured tests via the regular {@code --archive} pipeline.
 *
 * <p>This locks in the domain-model compatibility contract for Stage 1 of the
 * intended six-stage architecture: any future static-path-discovery module that
 * writes shared-model JSON in the documented snake_case shape will work end-to-end
 * with test-generator without further changes.
 *
 * <p>If this test breaks, either the manual seed in
 * {@code samples/scout/petclinic/manual-archive-seed/} drifted away from the
 * shared-model schema, or shared-model itself changed in a backward-incompatible way.
 */
class ManualArchiveSeedE2eTest {

    private static final String[] ENDPOINTS = {
            "GET:/api/owners",
            "GET:/api/owners/{ownerId}",
            "GET:/api/vets"
    };

    @Test
    void all_three_seeded_endpoints_compile() throws Exception {
        Path seed = locateManualSeed();
        ArchiveReader reader = ArchiveReader.load(seed);

        for (String endpoint : ENDPOINTS) {
            MultiPathSynthesisInput input =
                    reader.buildInput(endpoint, "com.example.petclinic.tests");
            String source = TestSynthesizer.synthesizeMulti(input);
            String className = extractClassName(source);
            CompileResult result = JavaSourceCompiler.compile(
                    "com.example.petclinic.tests." + className, source);
            assertThat(result.success())
                    .as("endpoint=%s diagnostics=%s", endpoint, result.diagnostics())
                    .isTrue();
        }
    }

    private static Path locateManualSeed() {
        Path candidate = Paths.get("samples/scout/petclinic/manual-archive-seed");
        if (Files.isDirectory(candidate)) return candidate;
        Path repoRelative = Paths.get("..", "samples/scout/petclinic/manual-archive-seed");
        if (Files.isDirectory(repoRelative)) return repoRelative;
        throw new IllegalStateException(
                "could not locate manual-archive-seed (cwd=" + Paths.get("").toAbsolutePath() + ")");
    }

    private static String extractClassName(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            int idx = trimmed.indexOf("class ");
            if (idx >= 0) {
                String tail = trimmed.substring(idx + 6);
                int sp = tail.indexOf(' ');
                int br = tail.indexOf('{');
                int end = (sp < 0) ? br : (br < 0 ? sp : Math.min(sp, br));
                if (end > 0) return tail.substring(0, end).trim();
            }
        }
        throw new IllegalStateException("no class declaration in synthesized source");
    }
}
