package io.graphrag.model;

/** JaCoCo 분기 좌표. branchIndex는 같은 라인의 분기 순번 (0-base). */
public record BranchRef(
        String classFqn,
        String method,
        int line,
        int branchIndex) {
}
