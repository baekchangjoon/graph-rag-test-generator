package io.graphrag.builder.provenance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * provenance 리포트 모델. 엔드포인트의 흐름 추적(guards, unguarded fields, unresolved references)을
 * 캡슐화한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProvenanceReport(
        String endpointId,
        List<GuardFact> guards,
        List<UnguardedField> unguarded,
        List<Unresolved> unresolved
) {

    /**
     * 가드 사실: 호출 위치, 연산, 피연산자들.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GuardFact(
            /**
             * 가드가 적용된 소스 위치. 형식: {@code "<file>:<line>"} (예: "TransferService.java:44")
             */
            String at,
            /**
             * 연산 이름 (예: "checkLimit", "validateAmount")
             */
            String op,
            /**
             * 연산의 피연산자 목록.
             */
            List<ValueRef> operands
    ) {
    }

    /**
     * 값 참조: 원점(origin), 경로, DB 정보, 호출 위치, 타입·의미 힌트.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValueRef(
            /**
             * 값의 원점.
             */
            Origin origin,
            /**
             * JSON 경로 (예: "$.user.id", "$.items[0].price")
             */
            String jsonPath,
            /**
             * DB 테이블명 (origin이 DB_READ일 때 의미 있음)
             */
            String table,
            /**
             * DB 컬럼명 (origin이 DB_READ일 때 의미 있음)
             */
            String column,
            /**
             * 호출 위치. 형식: {@code "<HTTP메서드> <pathLiteral>"}
             * (예: "POST /transfer", "GET /account/{id}", "DELETE /user/{id}/data")
             */
            String callSite,
            /**
             * stub 필드명 (origin이 EXTERNAL_RESPONSE일 때 의미 있음)
             */
            String stubField,
            /**
             * Java 타입명 (예: "java.lang.Long", "java.math.BigDecimal")
             */
            String javaType,
            /**
             * 의미 힌트 (예: "userId", "transferAmount", "fraud_score")
             */
            String semanticHint,
            /**
             * 리터럴 값 (예: "12345", "true")
             */
            String literal,
            /**
             * origin이 {@link Origin#DERIVED}일 때만 의미 있는 "concolic 채널 위임 표시"(REQ-032):
             * 이 파생식이 읽는 INPUT 리프의 dot-path 목록(예: {@code score * 2} → {@code ["score"]},
             * {@code score * factor} → {@code ["score", "factor"]}). 파생식 자신은 body의 어느 한
             * 필드가 아니므로 {@code jsonPath}로 표현할 수 없다 — 합성(C2)이 오라클 해를 "어느 입력
             * 필드에" 배치해야 하는지는 이 목록이 결정한다. DERIVED가 아니거나 INPUT 리프가 하나도
             * 없으면 null(직렬화 시 생략 — 기존 golden 스키마 무변경).
             */
            List<String> derivedFrom
    ) {

        /**
         * 9-arg 호환 생성자 — {@code derivedFrom}이 의미 없는 출처(INPUT/DB_READ/EXTERNAL_RESPONSE/
         * UNKNOWN)용. Jackson은 record의 canonical 생성자(10-arg)를 쓰므로 역직렬화에 영향 없다.
         */
        public ValueRef(Origin origin, String jsonPath, String table, String column, String callSite,
                        String stubField, String javaType, String semanticHint, String literal) {
            this(origin, jsonPath, table, column, callSite, stubField, javaType, semanticHint, literal, null);
        }
    }

    /**
     * 값의 원점 분류.
     */
    public enum Origin {
        /** 입력 요청(HTTP body, path, query, header) */
        INPUT,
        /** DB 조회 결과 */
        DB_READ,
        /** 외부 API 응답 */
        EXTERNAL_RESPONSE,
        /** 파생(입력 또는 DB로부터 계산) */
        DERIVED,
        /** 미분류 */
        UNKNOWN
    }

    /**
     * 가드되지 않은 필드: JSON 경로, 타입, 의미 힌트.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UnguardedField(
            /**
             * JSON 경로 (예: "$.riskScore", "$.internalFlag")
             */
            String jsonPath,
            /**
             * Java 타입명
             */
            String javaType,
            /**
             * 의미 힌트
             */
            String semanticHint
    ) {
    }

    /**
     * 미해결 참조: 분석하지 못한 이유와 대상 타입.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Unresolved(
            /**
             * 미해결 위치 (예: "DynamicService.java:15", "com.example.Util:reflectionCall")
             */
            String location,
            /**
             * 미해결 이유
             */
            Reason reason,
            /**
             * 대상 타입 (예: "ReflectedClass", "ProxyType")
             */
            String targetType
    ) {
    }

    /**
     * 미해결 이유 분류.
     */
    public enum Reason {
        /** 클래스패스에 없음 */
        NO_CLASSPATH,
        /** 리플렉션 사용 */
        REFLECTION,
        /** 프록시 객체 */
        PROXY,
        /** 다중 구현(인터페이스) */
        MULTI_IMPL,
        /** 깊이 한계 도달 */
        DEPTH_CAP
    }
}
