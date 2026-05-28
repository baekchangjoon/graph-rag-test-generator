package io.graphrag.orchestrator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLI entry point — runs the 6-stage pipeline in a coverage-feedback loop up to
 * {@code --max-iterations} times, generating {@code final-report.md} on exit.
 *
 * <pre>
 * orchestrator \
 *     --sut-source           ~/github_spring-petclinic/spring-petclinic/src/main/java \
 *     --project              petclinic \
 *     --test-package         com.example.petclinic.tests \
 *     --scout-config-template samples/scout/petclinic/template.yml \
 *     --scout-base-url       http://localhost:8084 \
 *     --out                  /tmp/graph-rag-iter \
 *     --coverage-target      0.70 \
 *     --max-iterations       3 \
 *     --scout-launcher-bin   ./scout-launcher/build/install/scout-launcher/bin/scout-launcher \
 *     --test-generator-bin   ./test-generator/build/install/test-generator/bin/test-generator \
 *     --user-test-command    "./gradlew :sut:test :sut:jacocoTestReport"
 * </pre>
 *
 * <p>Stages 1, 2, 6 run in-process. Stages 3 (scout-launcher), 4 (test-generator
 * per endpoint) and 5 (the user's test command + JaCoCo) run as subprocesses via
 * {@link ExternalStageRunner.Shell}.
 */
public final class Orchestrator {

    private static final Set<String> ALLOWED_FLAGS = Set.of(
            "--sut-source", "--project", "--test-package",
            "--scout-config-template", "--scout-base-url",
            "--out", "--coverage-target", "--max-iterations",
            "--scout-launcher-bin", "--test-generator-bin", "--user-test-command");

    private Orchestrator() {}

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
        try {
            OrchestratorConfig cfg = configFromFlags(flags);
            ExternalStageRunner external = externalFromFlags(flags);
            int code = runLoop(cfg, external, out);
            return code;
        } catch (IllegalArgumentException ex) {
            err.println("error: " + ex.getMessage());
            return 2;
        } catch (Exception ex) {
            err.println("error: " + ex.getMessage());
            ex.printStackTrace(err);
            return 4;
        }
    }

    /** Visible to tests so they can inject a fake {@link ExternalStageRunner}. */
    public static int runLoop(OrchestratorConfig cfg, ExternalStageRunner external,
                              PrintStream log)
            throws IOException, InterruptedException {
        Files.createDirectories(cfg.outDir());
        IterationRunner runner = new IterationRunner(cfg, external, log);

        List<IterationRunner.Outcome> outcomes = new ArrayList<>();
        List<List<String>> history = new ArrayList<>();
        Set<String> excludes = new HashSet<>();
        List<io.graphrag.feedback.MissingBranch> previousMissing = List.of();

        for (int i = 1; i <= cfg.maxIterations(); i++) {
            IterationRunner.Outcome o = runner.runOne(i, history, previousMissing, excludes);
            outcomes.add(o);
            if (o.delta() != null) {
                history.add(o.delta().newlyCovered());
                previousMissing = o.delta().stillMissing();
            }
            excludes = o.nextExcludes();
            if (o.decision().shouldTerminate()) {
                log.println("[orchestrator] terminating after iter " + i
                        + ": " + o.decision().reason());
                break;
            }
        }
        Path report = cfg.outDir().resolve("final-report.md");
        ReportGenerator.write(cfg, outcomes, report);
        log.println("[orchestrator] final report → " + report.toAbsolutePath());
        return 0;
    }

    private static OrchestratorConfig configFromFlags(Map<String, String> flags) {
        String[] required = {
                "--sut-source", "--project", "--test-package",
                "--scout-config-template", "--scout-base-url",
                "--out", "--coverage-target", "--max-iterations"
        };
        for (String r : required) {
            if (!flags.containsKey(r)) {
                throw new IllegalArgumentException("missing required flag " + r);
            }
        }
        return new OrchestratorConfig(
                Paths.get(flags.get("--sut-source")),
                flags.get("--project"),
                flags.get("--test-package"),
                Paths.get(flags.get("--scout-config-template")),
                flags.get("--scout-base-url"),
                Paths.get(flags.get("--out")),
                Double.parseDouble(flags.get("--coverage-target")),
                Integer.parseInt(flags.get("--max-iterations")));
    }

    private static ExternalStageRunner externalFromFlags(Map<String, String> flags) {
        String scoutBin = flags.get("--scout-launcher-bin");
        String tgBin = flags.get("--test-generator-bin");
        String userCmd = flags.get("--user-test-command");
        if (scoutBin == null || tgBin == null || userCmd == null) {
            throw new IllegalArgumentException(
                    "--scout-launcher-bin, --test-generator-bin and --user-test-command all required");
        }
        return new ExternalStageRunner.Shell(
                Paths.get(scoutBin), Paths.get(tgBin),
                Arrays.stream(userCmd.split("\\s+")).toList());
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
                  orchestrator \\
                    --sut-source            <src/main/java root> \\
                    --project               <project name> \\
                    --test-package          <pkg for generated tests> \\
                    --scout-config-template <template.yml without scout: section> \\
                    --scout-base-url        <http://host:port> \\
                    --out                   <output dir> \\
                    --coverage-target       <0.0..1.0> \\
                    --max-iterations        <int ≥1> \\
                    --scout-launcher-bin    <path to scout-launcher executable> \\
                    --test-generator-bin    <path to test-generator executable> \\
                    --user-test-command     <command line to run user's tests + jacoco>
                """;
    }
}
