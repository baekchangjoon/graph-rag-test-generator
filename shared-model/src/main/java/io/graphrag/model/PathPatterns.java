package io.graphrag.model;

/**
 * Spring Ant-style wildcard 경로 유틸리티.
 */
public final class PathPatterns {

    private PathPatterns() {
        // utility class
    }

    /**
     * Ant-style wildcard 세그먼트를 구체 probe 값으로 치환한다.
     * 세그먼트 단위로 정확히 {@code **} 또는 {@code *}인 경우만 대상으로 하며,
     * 일반 경로는 변경하지 않는다.
     *
     * <p>예: {@code /api/v1/orders/**} → {@code /api/v1/orders/probe}
     */
    public static String concretizeAntWildcards(String path) {
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if ("**".equals(segments[i]) || "*".equals(segments[i])) {
                segments[i] = "probe";
            }
        }
        return String.join("/", segments);
    }
}
