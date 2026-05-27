package io.graphrag.feedback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.JsonMappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageFeedbackTest {

    private static final ObjectMapper M = JsonMappers.standard();

    @Test
    void first_iteration_produces_delta_and_hints(@TempDir Path tmp) throws Exception {
        Path xml = locateResource("jacoco-sample.xml");
        Path out = tmp.resolve("iter-1");

        int exit = CoverageFeedback.run(new String[] {
                "--jacoco-xml",      xml.toString(),
                "--coverage-target", "0.85",
                "--out",             out.toString()
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(Files.exists(out.resolve("coverage-delta.json"))).isTrue();
        assertThat(Files.exists(out.resolve("termination-decision.json"))).isTrue();
        assertThat(Files.exists(out.resolve("next-iteration-hints.json"))).isTrue();

        CoverageDelta delta = M.readValue(Files.readAllBytes(out.resolve("coverage-delta.json")),
                new TypeReference<>() {});
        assertThat(delta.branchCoverage()).isEqualTo(0.5);
        assertThat(delta.stillMissing()).hasSize(2);
        // First iteration has no previous to compare → newly_covered always empty.
        assertThat(delta.newlyCovered()).isEmpty();
    }

    @Test
    void target_reached_omits_next_iteration_hints(@TempDir Path tmp) throws Exception {
        Path xml = locateResource("jacoco-sample.xml");
        Path out = tmp.resolve("iter-1");

        // sample report has branch coverage 0.5; force target_reached by setting a low target.
        int exit = CoverageFeedback.run(new String[] {
                "--jacoco-xml",      xml.toString(),
                "--coverage-target", "0.3",
                "--out",             out.toString()
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        TerminationDecision decision = M.readValue(
                Files.readAllBytes(out.resolve("termination-decision.json")),
                new TypeReference<>() {});
        assertThat(decision.shouldTerminate()).isTrue();
        assertThat(decision.reason()).isEqualTo("target_reached");
        // When terminating, downstream Stage 1 will not be re-invoked — no hints needed.
        assertThat(Files.exists(out.resolve("next-iteration-hints.json"))).isFalse();
    }

    @Test
    void prior_deltas_feed_into_newly_covered(@TempDir Path tmp) throws Exception {
        // Iteration 1: synthesize a coverage-delta.json carrying the still-missing branches
        // from the sample report.
        Path xml = locateResource("jacoco-sample.xml");
        Path iter1 = tmp.resolve("iter-1");
        Files.createDirectories(iter1);

        CoverageDelta synthetic = new CoverageDelta(
                java.util.List.of(),
                java.util.List.of(
                        new MissingBranch("com.example.petclinic.OwnerService:43", "OwnerService.java",
                                43, 1, 0),
                        new MissingBranch("com.example.petclinic.OwnerService:55", "OwnerService.java",
                                55, 2, 1)),
                0.4, 0.5);
        M.writerWithDefaultPrettyPrinter()
                .writeValue(iter1.resolve("coverage-delta.json").toFile(), synthetic);

        // Iteration 2: re-runs on the same sample → same still_missing → newly_covered stays
        // empty (no progress).
        Path iter2 = tmp.resolve("iter-2");
        int exit = CoverageFeedback.run(new String[] {
                "--jacoco-xml",          xml.toString(),
                "--coverage-target",     "0.85",
                "--out",                 iter2.toString(),
                "--previous-deltas-dir", tmp.toString()
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        CoverageDelta delta = M.readValue(Files.readAllBytes(iter2.resolve("coverage-delta.json")),
                new TypeReference<>() {});
        assertThat(delta.newlyCovered()).isEmpty();
    }

    private static Path locateResource(String name) {
        try {
            return Path.of(CoverageFeedbackTest.class.getClassLoader().getResource(name).toURI());
        } catch (Exception ex) {
            throw new IllegalStateException("missing test resource: " + name, ex);
        }
    }
}
