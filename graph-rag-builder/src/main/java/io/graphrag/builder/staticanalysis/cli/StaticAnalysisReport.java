package io.graphrag.builder.staticanalysis.cli;

import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult;
import io.graphrag.builder.staticanalysis.branch.ManualReviewItem;
import io.graphrag.builder.staticanalysis.domain.ClassRole;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * JSON shape written to {@code static-analysis-report.json} — execution
 * metadata + parse / analysis / generation counts + the manual-review queue.
 */
public record StaticAnalysisReport(
        String executionTimestamp,
        long executionDurationMs,
        String codeVersion,
        String project,
        Parsing parsing,
        Analysis analysis,
        PathGeneration pathGeneration,
        List<ManualReviewItem> manualReviewQueue) {

    public StaticAnalysisReport {
        Objects.requireNonNull(executionTimestamp, "executionTimestamp");
        Objects.requireNonNull(codeVersion, "codeVersion");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(parsing, "parsing");
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(pathGeneration, "pathGeneration");
        manualReviewQueue = List.copyOf(Objects.requireNonNull(manualReviewQueue, "manualReviewQueue"));
    }

    public record Parsing(int filesScanned, int filesParsed, int filesFailed,
                          List<ParseFailureItem> failures) {
        public Parsing {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }
    }

    public record ParseFailureItem(String path, String message) {
        public ParseFailureItem {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(message, "message");
        }
    }

    public record Analysis(int endpointsFound,
                           int controllersFound, int servicesFound,
                           int repositoriesFound, int domainsFound,
                           int branchesIdentified) {}

    public record PathGeneration(int totalPathsGenerated,
                                 int happyPaths, int boundaryPaths) {}

    public static StaticAnalysisReport from(AstParseResult ast,
                                            DomainAnalysisResult domain,
                                            BranchAnalysisResult branch,
                                            StaticAnalysisOptions opts,
                                            long durationMs) {
        int filesScanned = ast.parsedFiles().size() + ast.failures().size();
        List<ParseFailureItem> failureItems = ast.failures().stream()
                .map(f -> new ParseFailureItem(f.sourcePath().toString(), f.message()))
                .toList();

        int controllers = (int) domain.classRoles().values().stream()
                .filter(r -> r == ClassRole.CONTROLLER).count();
        int services    = (int) domain.classRoles().values().stream()
                .filter(r -> r == ClassRole.SERVICE).count();
        int repositories = (int) domain.classRoles().values().stream()
                .filter(r -> r == ClassRole.REPOSITORY).count();
        int domains     = (int) domain.classRoles().values().stream()
                .filter(r -> r == ClassRole.DOMAIN).count();
        int branchesIdentified = domain.methodAnalyses().values().stream()
                .mapToInt(m -> m.branches().size()).sum();

        long happy = branch.paths().stream()
                .filter(p -> p.id().endsWith("_happy")).count();
        long boundary = branch.paths().size() - happy;

        return new StaticAnalysisReport(
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                durationMs,
                opts.codeVersion(),
                opts.project(),
                new Parsing(filesScanned, ast.parsedFiles().size(),
                        ast.failures().size(), failureItems),
                new Analysis(domain.endpoints().size(),
                        controllers, services, repositories, domains,
                        branchesIdentified),
                new PathGeneration(branch.paths().size(),
                        (int) happy, (int) boundary),
                branch.manualReviewQueue());
    }
}
