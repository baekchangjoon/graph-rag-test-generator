package io.graphrag.builder.run;

import java.util.Map;

/**
 * 탐색이 캡처한 SELECT SQL에서 도출한 시드 타깃 해석.
 * path-string 휴리스틱을 대체해 ReadInputSynthesizer에 주입한다.
 *
 * @param table       시드할 테이블 (SELECT FROM 절)
 * @param paramColumn PATH/QUERY param 이름 → 그 param이 시드할 컬럼 (WHERE col=? 절)
 */
public record ResolutionHint(String table, Map<String, String> paramColumn) {
}
