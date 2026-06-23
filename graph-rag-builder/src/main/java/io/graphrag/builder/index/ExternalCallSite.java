package io.graphrag.builder.index;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

/**
 * SUT가 호출하는 외부 HTTP 의존의 정적 추출 결과.
 *
 * @param httpMethod 호출 HTTP method(GET/POST/...). exchange의 HttpMethod 인자가 변수/필드 참조라
 *                   정적 추출이 불가하면 빈 문자열.
 * @param pathLiteral URL 인자에서 추출한 정적 리터럴 path(query 제외, 예: "/inventory/stock").
 * @param responseShape 응답 역직렬화 타깃의 형상. class 리터럴이 아니거나(제네릭/변수) 모델에서
 *                      해결되지 않으면 empty.
 *
 * <p>Jackson 직렬화(StaticIndex 캐시)는 {@code responseShape}를 nullable {@code BodyShape}로 표현한다 —
 * 공용 {@code Json.mapper()}에 Jdk8Module(Optional)이 없어서, canonical 생성자를 우회하는 @JsonCreator로
 * null↔empty 매핑을 직접 처리한다.
 */
public record ExternalCallSite(String httpMethod, String pathLiteral,
        Optional<BodyShape> responseShape) {

    /** Jackson용 생성자: nullable responseShape를 Optional로 래핑(canonical 우회). */
    @JsonCreator
    public static ExternalCallSite of(
            @JsonProperty("httpMethod") String httpMethod,
            @JsonProperty("pathLiteral") String pathLiteral,
            @JsonProperty("responseShape") BodyShape responseShape) {
        return new ExternalCallSite(httpMethod, pathLiteral, Optional.ofNullable(responseShape));
    }

    /** Jackson 직렬화용: Optional 대신 nullable BodyShape를 노출(Jdk8Module 불요). */
    @JsonProperty("responseShape")
    @JsonIgnore(false)
    public BodyShape responseShapeOrNull() {
        return responseShape.orElse(null);
    }

    /** Optional accessor는 직렬화에서 제외(responseShapeOrNull이 단일 표현). */
    @Override
    @JsonIgnore
    public Optional<BodyShape> responseShape() {
        return responseShape;
    }
}
