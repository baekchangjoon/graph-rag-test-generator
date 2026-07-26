package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * {@link TripleSynthesizer}가 산출하는 후보 트리플 하나.
 *
 * <ul>
 *   <li>{@code body} — INPUT 출처 값이 배치된 요청 바디(JSON).</li>
 *   <li>{@code seedSqlStatements} — DB_READ 출처 값이 배치된 시드 {@code INSERT} 문장(순서=FK 부모 먼저).</li>
 *   <li>{@code stubMappings} — EXTERNAL_RESPONSE 출처 값이 배치된 WireMock mapping 형태의 스텁
 *       (스키마 엄격 검증·cap/정렬은 REQ-008/REQ-033 — 후속 task 범위).</li>
 *   <li>{@code notes} — 각 결정값의 가드 위치(trace) 근거를 사람이 읽을 수 있게 기록한 노트
 *       (notes.md 직렬화는 CLI 계층의 후속 task 범위).</li>
 * </ul>
 *
 * <p>갭 마커 문법({@code __AGENT_FILL__{...}})·후보 cap·우선순위 정렬은 REQ-007/REQ-033 — Task 9 범위.
 * 이 레코드 자체는 그 확장을 그대로 수용한다(필드 형상 변경 불요).
 */
public record TripleCandidate(ObjectNode body, List<String> seedSqlStatements,
                              List<ObjectNode> stubMappings, String notes) {
}
