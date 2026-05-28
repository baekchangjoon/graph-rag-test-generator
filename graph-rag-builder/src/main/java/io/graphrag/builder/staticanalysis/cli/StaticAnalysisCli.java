package io.graphrag.builder.staticanalysis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.builder.staticanalysis.branch.BoundaryValueConfig;
import io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult;
import io.graphrag.builder.staticanalysis.branch.BranchAnalyzer;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.DomainAnalyzer;
import io.graphrag.model.Endpoint;
import io.graphrag.model.JsonMappers;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;

/**
 * CLI entry point for the static analyzer. Invoked via
 * {@code java -cp graph-rag-builder.jar io.graphrag.builder.staticanalysis.cli.StaticAnalysisCli ...}.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — success, three JSON files written</li>
 *   <li>2 — flag parsing / usage error</li>
 *   <li>1 — runtime error (IO, etc.)</li>
 * </ul>
 */
public final class StaticAnalysisCli {

    /** Shared domain-model mapper (snake_case) — used for endpoints.json and paths.json. */
    private static final ObjectMapper M = JsonMappers.standard();

    /**
     * Camel-case mapper — used for static-analysis-report.json whose top-level
     * key names are asserted by the integration test (Map deserialization is
     * key-literal, so the JSON must carry camelCase keys).
     */
    private static final ObjectMapper REPORT_M;

    static {
        REPORT_M = new ObjectMapper();
        REPORT_M.registerModule(new JavaTimeModule());
        REPORT_M.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        REPORT_M.disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS);
    }

    private StaticAnalysisCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        StaticAnalysisOptions opts;
        try {
            opts = StaticAnalysisOptionsParser.parse(args);
        } catch (IllegalArgumentException ex) {
            err.println("error: " + ex.getMessage());
            err.println(StaticAnalysisOptionsParser.usage());
            return 2;
        }
        try {
            long startNanos = System.nanoTime();
            AstParseResult ast = AstParser.parse(opts.sutSource());
            DomainAnalysisResult domain = DomainAnalyzer.analyze(ast, opts.project());
            BranchAnalysisResult branch = BranchAnalyzer.analyze(
                    domain, opts.codeVersion(), opts.maxPathsPerEndpoint(),
                    BoundaryValueConfig.defaults(), opts.excludePaths());
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            StaticAnalysisReport report =
                    StaticAnalysisReport.from(ast, domain, branch, opts, durationMs);

            // Filter out excluded endpoints from the written list (paths are already filtered
            // by BranchAnalyzer).
            List<Endpoint> endpointsToWrite = domain.endpoints().stream()
                    .filter(ep -> !opts.excludePaths().contains(ep.id()))
                    .toList();

            Files.createDirectories(opts.out());
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(opts.out().resolve("endpoints.json").toFile(),
                            endpointsToWrite);
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(opts.out().resolve("paths.json").toFile(),
                            branch.paths());
            REPORT_M.writerWithDefaultPrettyPrinter()
                    .writeValue(opts.out().resolve("static-analysis-report.json").toFile(),
                            report);

            out.println("[static-analysis] "
                    + endpointsToWrite.size() + " endpoint(s), "
                    + branch.paths().size() + " path(s) → "
                    + opts.out().toAbsolutePath());
            return 0;
        } catch (IOException ex) {
            err.println("error: " + ex.getMessage());
            return 1;
        }
    }
}
