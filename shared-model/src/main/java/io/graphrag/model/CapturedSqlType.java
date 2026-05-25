package io.graphrag.model;

/**
 * 캡처된 SQL의 종류.
 */
public enum CapturedSqlType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    DDL
}
