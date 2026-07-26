package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * {@link TripleSynthesizer}가 산출하는 후보 트리플 하나.
 *
 * <ul>
 *   <li>{@code body} — INPUT 출처 값이 배치된 요청 바디(JSON). 결정 불가한 위치는 갭 마커
 *       ({@code "__AGENT_FILL__{...}"}, JSON 문자열 값 — REQ-007)로 표기된다.</li>
 *   <li>{@code seedSqlStatements} — DB_READ 출처 값이 배치된 시드 {@code INSERT} 문장(순서=FK 부모 먼저).
 *       어떤 가드도 결정하지 못한 NOT NULL 컬럼은 작은따옴표 문자열 리터럴 갭 마커로 표기된다(REQ-007).</li>
 *   <li>{@code stubMappings} — EXTERNAL_RESPONSE 출처 값이 배치된 WireMock mapping
 *       ({@code {"request":{"method","urlPath"},"response":{"status","jsonBody"}}}, 기존
 *       {@code StubMapping.buildFrom} 호환 — REQ-008). 만족 리터럴을 찾지 못하면 {@code jsonBody} 값이
 *       갭 마커다.</li>
 *   <li>{@code notes} — 각 결정값의 가드 위치(trace) 근거와 후보 순번(cand-NN)·결정 필드 수를 사람이
 *       읽을 수 있게 기록한 노트. CLI({@code synthesize-triple})가 이 문자열을 그대로 {@code notes.md}로
 *       직렬화한다.</li>
 * </ul>
 *
 * <p>이 레코드가 표현하는 후보 목록은 {@link TripleSynthesizer#synthesize}가 후보 cap(기본 4)·우선순위
 * 정렬(결정 필드 수 내림차순 → 사전순, cand-01=최우선 — REQ-033)을 적용한 뒤 반환한다. 레코드 자체의
 * 필드 형상은 Task 8 이후 변경되지 않았다 — cap/정렬/마커는 값 내용에 반영될 뿐 시그니처 확장이 필요
 * 없었다.
 */
public record TripleCandidate(ObjectNode body, List<String> seedSqlStatements,
                              List<ObjectNode> stubMappings, String notes) {
}
