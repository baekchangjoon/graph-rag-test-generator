/**
 * Standalone CLI entry point for the static analyzer. Wraps
 * {@code AstParser → DomainAnalyzer → BranchAnalyzer} and writes
 * {@code endpoints.json} + {@code paths.json} + {@code static-analysis-report.json}
 * to an output directory specified on the command line.
 */
package io.graphrag.builder.staticanalysis.cli;
