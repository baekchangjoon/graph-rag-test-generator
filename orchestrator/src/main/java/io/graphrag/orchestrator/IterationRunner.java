package io.graphrag.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.builder.staticanalysis.branch.BoundaryValueConfig;
import io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult;
import io.graphrag.builder.staticanalysis.branch.BranchAnalyzer;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.DomainAnalyzer;
import io.graphrag.feedback.CoverageDelta;
import io.graphrag.feedback.CoverageDeltaCalculator;
import io.graphrag.feedback.CoverageReport;
import io.graphrag.feedback.FocusHintGenerator;
import io.graphrag.feedback.JaCoCoXmlParser;
import io.graphrag.feedback.TerminationDecision;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;
import io.graphrag.translator.ScoutStepTranslator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        AstParseResult ast = AstParser.parse(cfg.sutSource());
        DomainAnalysisResult domain = DomainAnalyzer.analyze(ast, cfg.project());
        BranchAnalysisResult branch = BranchAnalyzer.analyze(
                domain,
                "iter-" + iterIndex,
                /* maxPathsPerEndpoint */ 10,
                BoundaryValueConfig.defaults(),
                excludePaths);
        // Defensive filter: drop paths whose endpoint URL has un-substituted {name}
        // placeholders that the static analyzer's SampleInputGenerator failed to fill.
        // Petclinic-style @PathVariable("ownerId") Owner owner — where the Java
        // parameter name (owner) diverges from the URL placeholder (ownerId) — is the
        // canonical trigger. Without this filter the translator throws and the whole
        // iteration aborts; with it, those endpoints are silently quarantined and the
        // loop continues on the bindable subset.
        Map<String, Endpoint> endpointById = new HashMap<>();
        for (Endpoint e : domain.endpoints()) endpointById.put(e.id(), e);
        List<ExploredPath> bindablePaths = new ArrayList<>();
        List<String> unboundSkipped = new ArrayList<>();
        for (ExploredPath p : branch.paths()) {
            Endpoint ep = endpointById.get(p.endpointId());
            if (ep == null) continue;
            Set<String> placeholders = extractPlaceholders(ep.path());
            if (placeholders.isEmpty()
                    || p.sampleInput().pathParams().keySet().containsAll(placeholders)) {
                bindablePaths.add(p);
            } else {
                Set<String> missing = new HashSet<>(placeholders);
                missing.removeAll(p.sampleInput().pathParams().keySet());
                unboundSkipped.add(p.id() + " (missing " + missing + ")");
            }
        }
        if (!unboundSkipped.isEmpty()) {
            log.println("[orchestrator] Stage 1 quarantined " + unboundSkipped.size()
                    + " unbound-path-template paths: " + unboundSkipped);
        }

        Set<String> bindableEndpointIds = new HashSet<>();
        for (ExploredPath p : bindablePaths) bindableEndpointIds.add(p.endpointId());
        List<Endpoint> endpointsToWrite = domain.endpoints().stream()
                .filter(e -> !excludePaths.contains(e.id()))
                .filter(e -> bindableEndpointIds.contains(e.id()))
                .toList();
        Files.createDirectories(layout.stage1Discovery());
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage1Endpoints().toFile(), endpointsToWrite);
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage1Paths().toFile(), bindablePaths);

        if (endpointsToWrite.isEmpty()) {
            log.println("[orchestrator] Stage 1 produced zero endpoints — halting iteration");
            return Outcome.zeroPaths(layout);
        }

        log.println("=== iter " + iterIndex + " — Stage 2 (translate to scout config) ===");
        ScoutStepTranslator.translate(
                layout.stage1Paths(), layout.stage1Endpoints(),
                cfg.scoutBaseUrl(),
                cfg.scoutConfigTemplate(),
                layout.stage2Config());

        // Stage 2 step (b): repoint scout-launcher's archive output at this iter's slot.
        // ExternalStageRunner.Shell.runScout ignores its archiveDir arg and just spawns
        // scout-launcher on the YAML, so the YAML itself has to carry the per-iter path.
        ObjectMapper iterYaml = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
        ObjectNode iterRoot = (ObjectNode) iterYaml.readTree(layout.stage2Config().toFile());
        iterRoot.with("output").put("archive-dir", layout.stage3Archive().toString());
        Files.write(layout.stage2Config(),
                iterYaml.writerWithDefaultPrettyPrinter().writeValueAsString(iterRoot).getBytes());

        log.println("=== iter " + iterIndex + " — Stage 3 (scout-launcher) ===");
        external.runScout(layout.stage2Config(), layout.stage3Archive());

        log.println("=== iter " + iterIndex + " — Stage 4 (test-generator per endpoint) ===");
        List<String> endpointIds = endpointsToWrite.stream().map(Endpoint::id).toList();
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

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)\\}");

    static Set<String> extractPlaceholders(String pathTemplate) {
        Set<String> out = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(pathTemplate);
        while (m.find()) out.add(m.group(1));
        return out;
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
