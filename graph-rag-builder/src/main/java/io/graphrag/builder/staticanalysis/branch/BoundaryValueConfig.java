package io.graphrag.builder.staticanalysis.branch;

import java.util.List;
import java.util.Objects;

/**
 * Per-type boundary value rules consumed by {@link BoundaryValueGenerator}.
 *
 * <p>v1 ships only static defaults via {@link #defaults()}; the parameterized
 * constructor exists so that a future session can override per-project (e.g.
 * to add Long.MIN_VALUE, double NaN, etc.) without changing the generator API.
 *
 * @param numericVariants  numeric boundary values excluding {@link #numericHappy}.
 *                         Used in order; v1: {@code ["-1", "0", "2147483647"]}.
 * @param numericHappy     happy-path numeric value (v1: {@code "1"}).
 * @param stringVariants   string boundary values excluding {@link #stringHappy}.
 *                         v1: {@code [""]}.
 * @param stringHappy      happy-path string value (v1: {@code "a"}).
 */
public record BoundaryValueConfig(
        List<String> numericVariants,
        String numericHappy,
        List<String> stringVariants,
        String stringHappy) {

    public BoundaryValueConfig {
        numericVariants = List.copyOf(Objects.requireNonNull(numericVariants, "numericVariants"));
        Objects.requireNonNull(numericHappy, "numericHappy");
        stringVariants  = List.copyOf(Objects.requireNonNull(stringVariants,  "stringVariants"));
        Objects.requireNonNull(stringHappy, "stringHappy");
    }

    public static BoundaryValueConfig defaults() {
        return new BoundaryValueConfig(
                List.of("-1", "0", String.valueOf(Integer.MAX_VALUE)),
                "1",
                List.of(""),
                "a");
    }
}
