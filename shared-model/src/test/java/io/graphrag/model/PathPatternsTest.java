package io.graphrag.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathPatternsTest {

    @Test
    void concretizeAntWildcards_replacesDoubleStarSegment() {
        assertThat(PathPatterns.concretizeAntWildcards("/api/v1/orders/**"))
                .isEqualTo("/api/v1/orders/probe");
    }

    @Test
    void concretizeAntWildcards_replacesSingleStarSegment() {
        assertThat(PathPatterns.concretizeAntWildcards("/api/*/orders"))
                .isEqualTo("/api/probe/orders");
    }

    @Test
    void concretizeAntWildcards_noWildcard_unchanged() {
        assertThat(PathPatterns.concretizeAntWildcards("/api/v1/orders"))
                .isEqualTo("/api/v1/orders");
    }

    @Test
    void concretizeAntWildcards_multipleWildcards() {
        assertThat(PathPatterns.concretizeAntWildcards("/**/orders/**"))
                .isEqualTo("/probe/orders/probe");
    }
}
