package io.graphrag.generator.compose;

import java.util.List;

/**
 * 생성될 테스트의 픽스처 한 줄.
 *
 * @param sql JDBC PreparedStatement 형식 (`?` placeholder 포함)
 * @param params 위치별 파라미터 값
 * @param affectedTable 정리 시 사용
 */
public record FixtureStatement(String sql, List<Object> params, String affectedTable) {}
