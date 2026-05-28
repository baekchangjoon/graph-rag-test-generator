package io.graphrag.feedback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.JsonMappers;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 5+6 entry point. Reads JaCoCo XML, computes the delta against the previous
 * iteration's still-missing branches, emits {@code coverage-delta.json} +
 * {@code next-iteration-hints.json}, and prints the termination decision.
 *
 * <pre>
 * coverage-feedback \
 *     --jacoco-xml          /path/to/jacoco.xml \
 *     --coverage-target     0.85 \
 *     --previous-deltas-dir /tmp/iter-runs \
 *     --out                 /tmp/iter-runs/iter-3
 * </pre>
 *
 * <p>{@code --previous-deltas-dir} is expected to contain one or more
 * {@code iter-*}/{@code coverage-delta.json} files; the parser reads them in
 * name order. Pass an empty / missing dir on the first iteration.
 */
public final class CoverageFeedback {

    private static final ObjectMapper M = JsonMappers.standard();

    private static final Set<String> ALLOWED_FLAGS = Set.of(
            "--jacoco-xml", "--coverage-target", "--previous-deltas-dir", "--out");
    private static final Set<String> REQUIRED_FLAGS = Set.of(
            "--jacoco-xml", "--coverage-target", "--out");

    private CoverageFeedback() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> flags;
        try {
            flags = parseFlags(args);
        } catch (IllegalArgumentException ex) {
            err.println("error: " + ex.getMessage());
            err.println(usage());
            return 2;
        }
        for (String req : REQUIRED_FLAGS) {
            if (!flags.containsKey(req)) {
                err.println("error: missing required flag " + req);
                err.println(usage());
                return 2;
            }
        }
        try {
            double target = Double.parseDouble(flags.get("--coverage-target"));
            CoverageReport current = JaCoCoXmlParser.parse(Paths.get(flags.get("--jacoco-xml")));
            List<MissingBranch> previousMissing = loadPreviousMissing(
                    flags.get("--previous-deltas-dir"));
            List<List<String>> historyNewlyCovered = loadHistoryNewlyCovered(
                    flags.get("--previous-deltas-dir"));

            CoverageDelta delta = CoverageDeltaCalculator.compute(current, previousMissing);
            historyNewlyCovered.add(delta.newlyCovered());
            TerminationDecision decision = TerminationDecision.decide(
                    delta.branchCoverage(), target, historyNewlyCovered);
            FocusHintGenerator.NextIterationHints hints =
                    FocusHintGenerator.generate(delta, List.of());

            Path outDir = Paths.get(flags.get("--out"));
            Files.createDirectories(outDir);
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(outDir.resolve("coverage-delta.json").toFile(), delta);
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(outDir.resolve("termination-decision.json").toFile(), decision);
            if (!decision.shouldTerminate()) {
                M.writerWithDefaultPrettyPrinter()
                        .writeValue(outDir.resolve("next-iteration-hints.json").toFile(), hints);
            }
            out.println(String.format(
                    "[coverage-feedback] branch=%.3f line=%.3f newly_covered=%d still_missing=%d terminate=%s reason=%s",
                    delta.branchCoverage(), delta.lineCoverage(),
                    delta.newlyCovered().size(), delta.stillMissing().size(),
                    decision.shouldTerminate(), decision.reason()));
            return 0;
        } catch (IOException ex) {
            err.println("error: " + ex.getMessage());
            return 4;
        } catch (NumberFormatException ex) {
            err.println("error: invalid --coverage-target: " + ex.getMessage());
            return 2;
        }
    }

    private static List<MissingBranch> loadPreviousMissing(String dirArg) throws IOException {
        if (dirArg == null) return List.of();
        Path dir = Paths.get(dirArg);
        if (!Files.isDirectory(dir)) return List.of();
        Path latest = findLatestDeltaFile(dir);
        if (latest == null) return List.of();
        CoverageDelta prev = M.readValue(Files.readAllBytes(latest),
                new TypeReference<>() {});
        return prev.stillMissing();
    }

    private static List<List<String>> loadHistoryNewlyCovered(String dirArg) throws IOException {
        List<List<String>> history = new ArrayList<>();
        if (dirArg == null) return history;
        Path dir = Paths.get(dirArg);
        if (!Files.isDirectory(dir)) return history;
        List<Path> deltas = listDeltasInOrder(dir);
        for (Path p : deltas) {
            CoverageDelta d = M.readValue(Files.readAllBytes(p), new TypeReference<>() {});
            history.add(d.newlyCovered());
        }
        return history;
    }

    private static Path findLatestDeltaFile(Path dir) throws IOException {
        List<Path> all = listDeltasInOrder(dir);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static List<Path> listDeltasInOrder(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("coverage-delta.json"))
                    .sorted()
                    .toList();
        }
    }

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected token: " + a);
            }
            if (!ALLOWED_FLAGS.contains(a)) {
                throw new IllegalArgumentException("unknown flag: " + a);
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + a);
            }
            out.put(a, args[++i]);
        }
        return out;
    }

    private static String usage() {
        return """
                usage:
                  coverage-feedback \\
                    --jacoco-xml          <jacoco.xml> \\
                    --coverage-target     <0.0..1.0> \\
                    --out                 <output dir> \\
                    [--previous-deltas-dir <dir containing iter-*/coverage-delta.json>]
                """;
    }
}
