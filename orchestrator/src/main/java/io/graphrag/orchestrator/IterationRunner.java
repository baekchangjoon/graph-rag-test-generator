package io.graphrag.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.discovery.PathDiscoveryStatic;
import io.graphrag.feedback.CoverageDelta;
import io.graphrag.feedback.CoverageDeltaCalculator;
import io.graphrag.feedback.CoverageReport;
import io.graphrag.feedback.FocusHintGenerator;
import io.graphrag.feedback.JaCoCoXmlParser;
import io.graphrag.feedback.TerminationDecision;
import io.graphrag.model.Endpoint;
import io.graphrag.model.JsonMappers;
import io.graphrag.translator.ScoutStepTranslator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs one iteration end-to-end. Owns the in-process stages 1, 2, 6 directly and
 * delegates the external stages 3, 4, 5 to {@link ExternalStageRunner}. Returns the
 * iteration's {@link TerminationDecision} so the outer loop can decide whether to
 * continue.
 *
 * <p>Failure in any stage short-circuits the iteration: a partial archive is left
 * on disk for debugging, but no later stages run for that iteration. The orchestrator
 * counts that as an immediate stop so the next iteration's input isn't garbage.
 */
final class IterationRunner {

    private static final ObjectMapper M = JsonMappers.standard();

    private final OrchestratorConfig cfg;
    private final ExternalStageRunner external;
    private final PrintStream log;

    IterationRunner(OrchestratorConfig cfg, ExternalStageRunner external, PrintStream log) {
        this.cfg = cfg;
        this.external = external;
        this.log = log;
    }

    /**
     * @param iterIndex 1-based iteration number
     * @param history   newly_covered for all completed iterations so far. The
     *                  TerminationDecision needs the most recent 2 to detect
     *                  no-progress.
     * @param previousStillMissing the missing branches the previous iteration
     *                             reported, used for delta calculation. Pass
     *                             {@code List.of()} on iteration 1.
     * @param excludePaths Stage 1 will skip endpoint ids in this set.
     */
    Outcome runOne(int iterIndex,
                   List<List<String>> history,
                   List<io.graphrag.feedback.MissingBranch> previousStillMissing,
                   Set<String> excludePaths) throws IOException, InterruptedException {
        IterationLayout layout = new IterationLayout(
                cfg.outDir().resolve("iter-" + iterIndex));
        Files.createDirectories(layout.iterRoot());

        log.println("\n=== iter " + iterIndex + " — Stage 1 (path discovery) ===");
        PathDiscoveryStatic.Result discovery = PathDiscoveryStatic.discover(
                cfg.sutSource(), cfg.project(),
                "iter-" + iterIndex,
                excludePaths);
        Files.createDirectories(layout.stage1Discovery());
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage1Endpoints().toFile(), discovery.endpoints());
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage1Paths().toFile(), discovery.paths());

        if (discovery.endpoints().isEmpty()) {
            log.println("[orchestrator] Stage 1 produced zero endpoints — halting iteration");
            return Outcome.zeroPaths(layout);
        }

        log.println("=== iter " + iterIndex + " — Stage 2 (translate to scout config) ===");
        ScoutStepTranslator.translate(
                layout.stage1Paths(), layout.stage1Endpoints(),
                cfg.scoutBaseUrl(),
                cfg.scoutConfigTemplate(),
                layout.stage2Config());

        log.println("=== iter " + iterIndex + " — Stage 3 (scout-launcher) ===");
        external.runScout(layout.stage2Config(), layout.stage3Archive());

        log.println("=== iter " + iterIndex + " — Stage 4 (test-generator per endpoint) ===");
        List<String> endpointIds = discovery.endpoints().stream().map(Endpoint::id).toList();
        external.runTestGenerator(layout.stage3Archive(), endpointIds,
                cfg.testPackage(), layout.stage4Tests());

        log.println("=== iter " + iterIndex + " — Stage 5 (tests + JaCoCo) ===");
        external.runTestsAndJacoco(layout.stage4Tests(), layout.stage5Jacoco());

        log.println("=== iter " + iterIndex + " — Stage 6 (coverage feedback) ===");
        Files.createDirectories(layout.stage6Feedback());
        CoverageReport current = JaCoCoXmlParser.parse(layout.stage5Jacoco());
        CoverageDelta delta = CoverageDeltaCalculator.compute(current, previousStillMissing);
        List<List<String>> nextHistory = new ArrayList<>(history);
        nextHistory.add(delta.newlyCovered());
        TerminationDecision decision = TerminationDecision.decide(
                delta.branchCoverage(), cfg.coverageTarget(), nextHistory);
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage6Delta().toFile(), delta);
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage6Decision().toFile(), decision);
        Set<String> nextExcludes = new HashSet<>(excludePaths);
        if (!decision.shouldTerminate()) {
            FocusHintGenerator.NextIterationHints hints =
                    FocusHintGenerator.generate(delta, new ArrayList<>(excludePaths));
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(layout.stage6Hints().toFile(), hints);
            nextExcludes.addAll(hints.excludePaths());
        }
        log.printf("[orchestrator] iter %d: branch=%.3f newly_covered=%d still_missing=%d terminate=%s%n",
                iterIndex, delta.branchCoverage(),
                delta.newlyCovered().size(), delta.stillMissing().size(),
                decision.shouldTerminate());
        return new Outcome(layout, delta, decision, nextExcludes);
    }

    static List<io.graphrag.feedback.MissingBranch> loadStillMissing(IterationLayout layout)
            throws IOException {
        if (!Files.exists(layout.stage6Delta())) return List.of();
        CoverageDelta d = M.readValue(Files.readAllBytes(layout.stage6Delta()),
                new TypeReference<>() {});
        return d.stillMissing();
    }

    /** Per-iteration outcome the outer loop reads. */
    record Outcome(IterationLayout layout, CoverageDelta delta,
                   TerminationDecision decision, Set<String> nextExcludes) {

        static Outcome zeroPaths(IterationLayout layout) {
            return new Outcome(layout, null,
                    new TerminationDecision(true, "zero_paths_discovered", false),
                    Set.of());
        }
    }
}
