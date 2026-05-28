/**
 * Stage 3 of the static analyzer: deterministic boundary-value {@code SampleInput}
 * generation per endpoint plus the {@code PathExplorer} SPI implementation.
 *
 * <p>{@link io.graphrag.builder.staticanalysis.branch.BranchAnalyzer} consumes the
 * {@link io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult} from Stage 2
 * and produces a {@link io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult}
 * containing one happy {@code ExploredPath} per endpoint plus one variant per
 * numeric / string path/query parameter. Output is deterministic — slug ids match
 * {@code static_{handlerMethod}_{variant}} per the work-order convention.
 *
 * <p>{@link io.graphrag.builder.staticanalysis.branch.StaticAnalysisPathExplorer}
 * exposes the same generation surface through the
 * {@link io.graphrag.builder.exploration.PathExplorer} SPI.
 */
package io.graphrag.builder.staticanalysis.branch;
