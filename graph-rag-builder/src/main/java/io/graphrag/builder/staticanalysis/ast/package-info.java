/**
 * Stage 1 of the static analyzer: JavaParser-driven AST extraction.
 *
 * <p>{@link io.graphrag.builder.staticanalysis.ast.AstParser} walks a Spring
 * source tree, parses each {@code .java} file deterministically, and returns
 * {@link io.graphrag.builder.staticanalysis.ast.AstParseResult} containing the
 * successfully-parsed {@link com.github.javaparser.ast.CompilationUnit}s plus
 * any per-file failures (un-parseable files are isolated, not fatal).
 *
 * <p>Downstream package {@code io.graphrag.builder.staticanalysis.domain}
 * consumes the AST collection to classify class roles, extract Spring
 * endpoints, and compute branches + call graph.
 */
package io.graphrag.builder.staticanalysis.ast;
