package io.graphrag.discovery.heuristic;

import io.graphrag.discovery.HandlerParam;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Generates a small, deterministic set of "interesting" values for a handler parameter,
 * intended to be turned into separate {@code ExploredPath}s. The point is to nudge the
 * downstream scout into hitting validation / null-handling / overflow branches that the
 * happy path alone never reaches.
 *
 * <p>Why this is a flat enumeration and not a fancy search: the workorder explicitly
 * favors a "AST + boundary-value heuristic" approach over symbolic execution
 * (graph-rag-test-generator-risks.md R1). Anything cleverer (sat-solver, fuzzer) is out
 * of scope for T4; coverage feedback (T5) is what closes the loop.
 *
 * <p>Numeric types covered: {@code int}, {@code Integer}, {@code long}, {@code Long},
 * {@code short}, {@code Short}, {@code byte}, {@code Byte}, {@code double}, {@code float}
 * (boxed and unboxed). Anything else returns a single default value.
 */
public final class BoundaryValueGenerator {

    private BoundaryValueGenerator() {}

    public static Set<String> generate(HandlerParam param) {
        // Preserve insertion order — Set is iterated in {happy, edges...} sequence so a
        // round-trip through paths.json keeps a stable variant order.
        Set<String> out = new LinkedHashSet<>();
        out.add(happyValueFor(param));
        if (isNumeric(param.typeName())) {
            out.add("0");
            out.add("-1");
            out.add("2147483647");
            out.add(""); // empty string — Spring usually 400s for missing-required numerics
        }
        return out;
    }

    /**
     * Returns true iff the source-level type name parses as one of the common numeric
     * primitives or their boxed equivalents.
     */
    public static boolean isNumeric(String typeName) {
        if (typeName == null) return false;
        return switch (typeName) {
            case "int", "Integer",
                 "long", "Long",
                 "short", "Short",
                 "byte", "Byte",
                 "double", "Double",
                 "float", "Float" -> true;
            default -> false;
        };
    }

    private static String happyValueFor(HandlerParam param) {
        if (isNumeric(param.typeName())) return "1";
        // Default for String / UUID / enum / anything else — petclinic-style identifiers
        // are usually integers but a "1" string also works as a UUID stand-in. The
        // downstream test-generator will assert on response status, not on the value
        // round-tripping.
        return "1";
    }
}
