package io.graphrag.model;

/** prepared statement 바인딩 1개. position은 1-base. */
public record SqlBinding(
        int position,
        String column,
        String value,
        BindingOrigin origin) {
}
