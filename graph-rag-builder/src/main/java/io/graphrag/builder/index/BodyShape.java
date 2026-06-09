package io.graphrag.builder.index;

import java.util.List;

/** @RequestBody 타입의 필드 구조. sample input 합성에 사용 (builder 내부 전용). */
public record BodyShape(String javaType, List<BodyField> fields) {

    public record BodyField(String name, String javaType) {
    }
}
