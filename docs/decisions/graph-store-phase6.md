# 의사결정: Phase 6.1 그래프 스토어 — 파티션 샤드 파일 스토어, Neo4j 보류

날짜: 2026-06-11 / 단계: Phase 6.1 "분산 그래프 스토어 검토" (roadmap 09,
docs/10 점진 이식 단계 6a)

## 결정

`GraphStore` 경계를 유지한 채 `PartitionedGraphStore`(global.json + 파티션별
`partitions/<패키지>.json` 샤드)를 추가한다. Neo4j 등 그래프 DB 도입은
Phase 6b 조건이 충족될 때까지 보류한다.

## 검토 내용

| 후보 | 평가 |
|---|---|
| 단일 graph.json 유지 | 5M 라인에서 단일 JSON 직렬화/로드가 메모리·시간 병목. 증분 갱신 시 전체 재직렬화 |
| **파티션 샤드 파일 (채택)** | 모듈/패키지 단위 분할로 쓰기·갱신이 파티션 국소화. 증분 빌드(6.2)의 단위와 일치. 의존성 추가 없음, 결정성 검증(byte 비교) 유지 |
| Neo4j causal cluster | 수천만 노드의 전역 질의에는 결국 필요하나, 현 질의 패턴은 전부 id/endpoint 필터의 구조 질의(FileGraphRagClient)로 충분. 이전 시도에서 Neo4j 의존이 테스트 skip 유발한 마찰 기록(`graph-store-phase1.md` 참조). docs/10의 6a(PoC)는 "file-system archive 그대로"가 전제 |

## 파티셔닝 규칙

- 파티션 키 = 핸들러 클래스의 패키지 (`GraphPartitioner.partitionOf`)
- 소속: endpoint/wsEndpoint → 핸들러 패키지; path/wsExchange → 소유 endpoint;
  sql/httpCall → 소유 path. 전역 사실(tables, mappers, sutId, commitSha)은 global.json
- 호환: `graph.json`(병합본)도 함께 저장 → 도구 2(FileGraphRagClient) 무수정

## Neo4j 복귀 조건 (6b)

- 단일 호스트 메모리로 병합 로드가 불가능해지는 규모 (수천만 노드)
- 전역 호출 그래프 횡단 질의(1-2 hop 영향 분석을 DB로 수행)가 필요해지는 시점
- 교체 비용은 GraphStore/GraphRagClient 구현체 추가로 한정 (경계 유지)
