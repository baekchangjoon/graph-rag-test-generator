package io.graphrag.builder.staticanalysis.branch;

import java.util.List;
import java.util.Set;

/**
 * Pure static helpers that turn a parameter type name into the happy + variant
 * values declared in {@link BoundaryValueConfig}. Anything that isn't numeric or
 * string-like falls through to empty strings — the caller treats this as
 * "no boundary variant for this param" and may emit a {@link ManualReviewItem}.
 */
public final class BoundaryValueGenerator {

    private static final Set<String> NUMERIC = Set.of(
            "int", "Integer",
            "long", "Long",
            "short", "Short",
            "byte", "Byte",
            "double", "Double",
            "float", "Float");

    private static final Set<String> STRING_LIKE = Set.of(
            "String", "CharSequence");

    private BoundaryValueGenerator() {}

    public static boolean isNumeric(String typeName) {
        return typeName != null && NUMERIC.contains(typeName);
    }

    public static boolean isStringLike(String typeName) {
        return typeName != null && STRING_LIKE.contains(typeName);
    }

    public static String happy(String typeName, BoundaryValueConfig cfg) {
        if (isNumeric(typeName))    return cfg.numericHappy();
        if (isStringLike(typeName)) return cfg.stringHappy();
        return "";
    }

    public static List<String> variants(String typeName, BoundaryValueConfig cfg) {
        if (isNumeric(typeName))    return cfg.numericVariants();
        if (isStringLike(typeName)) return cfg.stringVariants();
        return List.of();
    }
}
