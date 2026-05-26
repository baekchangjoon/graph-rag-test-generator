package io.graphrag.builder.exploration;

/**
 * 단순 분기 타겟 — 입력값에 따라 다른 경로를 실행. {@link JacocoCoverageScorer} 검증에 사용.
 */
public class BranchTarget {

    public String classify(int value) {
        if (value < 0) {
            return "negative";
        } else if (value == 0) {
            return "zero";
        } else if (value < 10) {
            return "small";
        } else {
            return "large";
        }
    }
}
