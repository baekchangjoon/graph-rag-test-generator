package io.graphrag.generator.compose;

import java.util.List;

/** 템플릿에 채울 가변 길이 슬롯들 (docs/04 방식 C의 "프로그램" 산출물). */
public record ComposedFixture(
        List<Var> vars,
        List<Stmt> inserts,
        List<Stmt> deletes,
        String bodyFormat,
        List<String> bodyArgExprs,
        List<Assertion> assertions) {

    /** 테스트 코드의 지역 변수. valueExpr는 Java 표현식 텍스트. */
    public record Var(String name, String valueExpr) {
    }

    /** jdbc().update 1줄. argExprs는 Java 표현식 텍스트 (변수명 또는 리터럴). */
    public record Stmt(String sql, List<String> argExprs) {
    }

    public record Assertion(String jsonPath, String matcher) {
    }
}
