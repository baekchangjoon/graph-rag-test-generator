package io.graphrag.builder.index;

import java.util.List;

/** @RequestBody 타입의 필드 구조. sample input 합성에 사용 (builder 내부 전용). */
public record BodyShape(String javaType, List<BodyField> fields, boolean collection) {

    /** 객체 바디 편의 생성자(기존 호출부 호환): collection=false. */
    public BodyShape(String javaType, List<BodyField> fields) {
        this(javaType, fields, false);
    }

    public record BodyField(String name, String javaType) {
    }
}
