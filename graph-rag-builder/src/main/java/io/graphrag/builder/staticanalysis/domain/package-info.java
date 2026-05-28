/**
 * Stage 2 of the static analyzer: domain-model classification + endpoint
 * extraction.
 *
 * <p>{@link io.graphrag.builder.staticanalysis.domain.DomainAnalyzer} takes
 * the {@link io.graphrag.builder.staticanalysis.ast.AstParseResult} from
 * Stage 1 and produces {@link io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult}:
 * a deterministic list of {@code shared-model} {@code Endpoint}s plus the
 * {@code MethodAnalysis} / {@code CallGraph} structures that Stage 3 (branch
 * → sample input generation, future session) will consume.
 */
package io.graphrag.builder.staticanalysis.domain;
