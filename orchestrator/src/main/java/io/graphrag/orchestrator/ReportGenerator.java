package io.graphrag.orchestrator;

import io.graphrag.feedback.CoverageDelta;
import io.graphrag.feedback.MissingBranch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders {@code final-report.md} from the per-iteration {@link IterationRunner.Outcome}s.
 *
 * <p>Layout:
 * <pre>
 *   # graph-rag iteration report — &lt;project&gt;
 *   - target = 0.85, max-iterations = 5
 *   ## Per-iteration coverage
 *   | iter | branch | newly_covered | still_missing | terminate |
 *   ## still-missing branches (final iteration)
 *   | branch_id | source | line |
 * </pre>
 */
final class ReportGenerator {

    private ReportGenerator() {}

    static void write(OrchestratorConfig cfg, List<IterationRunner.Outcome> outcomes,
                      Path outFile) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("# graph-rag iteration report — ").append(cfg.project()).append("\n\n");
        s.append("- coverage-target: ").append(cfg.coverageTarget()).append("\n");
        s.append("- max-iterations: ").append(cfg.maxIterations()).append("\n");
        s.append("- iterations completed: ").append(outcomes.size()).append("\n\n");

        s.append("## Per-iteration coverage\n\n");
        s.append("| iter | branch | line | newly_covered | still_missing | terminate | reason |\n");
        s.append("|-----:|------:|-----:|--------------:|--------------:|:----------|:-------|\n");
        for (int i = 0; i < outcomes.size(); i++) {
            IterationRunner.Outcome o = outcomes.get(i);
            CoverageDelta d = o.delta();
            String row;
            if (d == null) {
                row = String.format("| %d | — | — | — | — | yes | %s |%n",
                        i + 1, o.decision().reason());
            } else {
                row = String.format("| %d | %.3f | %.3f | %d | %d | %s | %s |%n",
                        i + 1, d.branchCoverage(), d.lineCoverage(),
                        d.newlyCovered().size(), d.stillMissing().size(),
                        o.decision().shouldTerminate() ? "yes" : "no",
                        o.decision().reason() == null ? "-" : o.decision().reason());
            }
            s.append(row);
        }
        s.append('\n');

        // Final iteration's still_missing (if any non-zero-paths iteration succeeded).
        IterationRunner.Outcome last = null;
        for (int i = outcomes.size() - 1; i >= 0; i--) {
            if (outcomes.get(i).delta() != null) {
                last = outcomes.get(i);
                break;
            }
        }
        if (last != null) {
            List<MissingBranch> missing = last.delta().stillMissing();
            s.append("## still-missing branches (last iteration: ")
                    .append(missing.size()).append(")\n\n");
            if (missing.isEmpty()) {
                s.append("None — target reached or full coverage achieved.\n");
            } else {
                s.append("| branch_id | source | line | branches_missed |\n");
                s.append("|:----------|:-------|----:|---------------:|\n");
                // Cap the visible list — beyond ~50 the table becomes useless.
                int cap = Math.min(missing.size(), 50);
                for (int i = 0; i < cap; i++) {
                    MissingBranch mb = missing.get(i);
                    s.append(String.format("| %s | %s | %d | %d |%n",
                            mb.branchId(), mb.sourceFile(), mb.line(), mb.branchesMissed()));
                }
                if (cap < missing.size()) {
                    s.append("\n_(").append(missing.size() - cap).append(" more, see ")
                            .append("iter-N/stage6-feedback/coverage-delta.json)_\n");
                }
            }
        }
        Files.writeString(outFile, s.toString());
    }
}
