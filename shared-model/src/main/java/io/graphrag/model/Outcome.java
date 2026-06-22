package io.graphrag.model;

/** 응답 분류 결과. ResponseClassifier가 HTTP 응답을 판정한 산출물. */
public record Outcome(Kind kind, int semanticStatus, String semanticStatusText, String signal) {

    public enum Kind { SUCCESS, FAILURE }

    public static Outcome success(int status) {
        return new Outcome(Kind.SUCCESS, status, String.valueOf(status), "status");
    }
}
