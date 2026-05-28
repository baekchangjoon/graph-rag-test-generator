package io.graphrag.builder.staticanalysis.domain;

import java.util.Objects;

/** Method return type. {@code isVoid} is convenience derived from {@code type.equals("void")}. */
public record ReturnType(String type, boolean isVoid) {

    public ReturnType {
        Objects.requireNonNull(type, "type");
    }

    public static ReturnType of(String type) {
        return new ReturnType(type, "void".equals(type));
    }
}
