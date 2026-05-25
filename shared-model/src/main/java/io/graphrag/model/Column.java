package io.graphrag.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * DB 테이블 컬럼. SCHEMAS.md의 Column 표현.
 *
 * <p>JSON에서는 {@code default} 필드명 사용 (Java keyword 회피용 {@code defaultValue}).
 *
 * @param type 운영 DBMS 기준 타입 표현 (예: "varchar(20)", "bigint")
 * @param defaultValue 컬럼의 기본값. 없으면 null.
 */
public record Column(
        String name,
        String type,
        boolean nullable,
        @JsonProperty("default") Object defaultValue) {

    public Column {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }
}
