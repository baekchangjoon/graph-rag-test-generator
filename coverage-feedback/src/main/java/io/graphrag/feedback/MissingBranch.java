package io.graphrag.feedback;

/**
 * A source line where JaCoCo recorded one or more uncovered branches.
 *
 * @param branchId    stable id of the form {@code package.SourceFile:line} — keep this
 *                    consistent across iterations so {@link CoverageDeltaCalculator}
 *                    can do set diffs
 * @param sourceFile  the JaCoCo {@code sourcefile.name} attribute (e.g. {@code Owner.java})
 * @param line        1-based line number
 * @param branchesMissed JaCoCo {@code mb} count for this line
 * @param branchesCovered JaCoCo {@code cb} count for this line
 */
public record MissingBranch(String branchId, String sourceFile, int line,
                            int branchesMissed, int branchesCovered) {
}
