package io.graphrag.model;

public enum ParamKind {
    BODY, PATH, QUERY, HEADER,
    /** @Controller 폼 커맨드-객체(application/x-www-form-urlencoded). 필드는 BODY처럼 합성·변이, 전송만 form-encoded. */
    FORM
}
