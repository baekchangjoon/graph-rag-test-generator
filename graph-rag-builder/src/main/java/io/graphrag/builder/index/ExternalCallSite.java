package io.graphrag.builder.index;

import java.util.Optional;

/**
 * SUT가 호출하는 외부 HTTP 의존의 정적 추출 결과.
 *
 * @param httpMethod 호출 HTTP method(GET/POST/...). exchange의 HttpMethod 인자가 변수/필드 참조라
 *                   정적 추출이 불가하면 빈 문자열.
 * @param pathLiteral URL 인자에서 추출한 정적 리터럴 path(query 제외, 예: "/inventory/stock").
 * @param responseShape 응답 역직렬화 타깃의 형상. class 리터럴이 아니거나(제네릭/변수) 모델에서
 *                      해결되지 않으면 empty.
 */
public record ExternalCallSite(String httpMethod, String pathLiteral,
        Optional<BodyShape> responseShape) {
}
