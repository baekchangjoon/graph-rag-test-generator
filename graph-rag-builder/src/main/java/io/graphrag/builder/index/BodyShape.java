package io.graphrag.builder.index;

import java.util.List;

/** @RequestBody 타입의 필드 구조. sample input 합성에 사용 (builder 내부 전용). */
public record BodyShape(String javaType, List<BodyField> fields, boolean collection) {

    /** 객체 바디 편의 생성자(기존 호출부 호환): collection=false. */
    public BodyShape(String javaType, List<BodyField> fields) {
        this(javaType, fields, false);
    }

    /**
     * 바디 타입을 해석할 수 없는 함수형 라우트용 빈 합성 shape.
     * explore 단계가 엔드포인트를 건너뛰지 않도록 BODY 파라미터와 함께 사용한다.
     */
    public static BodyShape empty() {
        return new BodyShape("io.graphrag.synthetic.Body", List.of());
    }

    public record BodyField(String name, String javaType) {
    }
}
