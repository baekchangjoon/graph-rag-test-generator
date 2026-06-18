package io.graphrag.generator.compose;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 템플릿에 채울 가변 길이 슬롯들 (docs/04 방식 C의 "프로그램" 산출물). */
public record ComposedFixture(
        List<Var> vars,
        List<Stmt> inserts,
        List<Stmt> deletes,
        String bodyFormat,
        List<String> bodyArgExprs,
        List<Assertion> assertions,
        // 캡처값 → 런타임 Java 표현식 (예: "probe-userId" → scope.testId() + "-user").
        // Kafka emit 단언의 key/payload에서 입력 유래 비결정 값을 테스트 런타임 값으로 치환할 때 쓴다.
        Map<String, String> substitutions,
        // DB 시퀀스 PK처럼 환경마다 달라지는 비결정 캡처값 (emit payload 단언에서 제외 대상).
        Set<String> nonDeterministicValues) {

    /** 기존 6-arg 호출부 호환 (substitutions/nonDeterministicValues 없음). */
    public ComposedFixture(List<Var> vars, List<Stmt> inserts, List<Stmt> deletes,
                           String bodyFormat, List<String> bodyArgExprs, List<Assertion> assertions) {
        this(vars, inserts, deletes, bodyFormat, bodyArgExprs, assertions, Map.of(), Set.of());
    }

    /** 테스트 코드의 지역 변수. valueExpr는 Java 표현식 텍스트. */
    public record Var(String name, String valueExpr) {
    }

    /** jdbc().update 1줄. argExprs는 Java 표현식 텍스트 (변수명 또는 리터럴). */
    public record Stmt(String sql, List<String> argExprs) {
    }

    public record Assertion(String jsonPath, String matcher) {
    }
}
