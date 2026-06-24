package io.graphrag.builder.capture.trace;

import java.util.regex.Pattern;

public final class TraceReceiverLimits {
    private TraceReceiverLimits() {}

    public static final int MAX_TRACES = 50_000;
    public static final int MAX_SPANS_PER_TRACE = 10_000;
    public static final Pattern HEX_32 = Pattern.compile("[0-9a-f]{32}");
}
