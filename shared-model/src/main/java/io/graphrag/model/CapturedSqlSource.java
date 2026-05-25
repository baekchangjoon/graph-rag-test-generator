package io.graphrag.model;

/**
 * 캡처된 SQL이 어디서 발행됐는지 식별.
 *
 * <p>도구 2가 픽스처 합성 방식을 결정할 때 참고
 * (예: JPA 유래는 JDBC 직접 INSERT, MyBatis XML 유래는 @Sql 파일 등).
 */
public enum CapturedSqlSource {
    JPA_REPOSITORY_DERIVED,
    JPA_QUERY_ANNOTATION,
    JPA_CRITERIA,
    JPA_ENTITYMANAGER,
    MYBATIS_XML_MAPPER,
    MYBATIS_ANNOTATION,
    JDBC_TEMPLATE,
    JDBC_RAW
}
