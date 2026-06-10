# 의사결정: Phase 1 그래프 영속은 JSON 파일 유지

날짜: 2026-06-10 / 단계: Phase 1 진입 결정 (roadmap 09: "Phase 1 진입 전 graph/vector store 선택")

## 결정

`GraphStore` 인터페이스 + `JsonFileGraphStore`(단일 graph.json)를 Phase 1에서도
유지한다. Neo4j 등 그래프 DB와 vector store 도입은 보류.

## 근거

- Phase 1 규모(100K 프로젝트, endpoint 수십~수백)에서 단일 JSON은 조회·디버깅
  모두 충분하고 결정성 검증(byte 비교)이 쉽다
- 현재 질의 패턴은 전부 구조 질의(id/endpoint 필터)로, FileGraphRagClient가 처리.
  임베딩 기반 유사도 질의가 등장하기 전에는 vector store가 불필요 (YAGNI)
- 이전 시도에서 Neo4j 의존이 테스트 skip을 유발한 마찰 기록

## 복귀 조건

- Phase 6(5M 라인 레거시): 노드 수천만 → 분산 그래프 스토어 검토 (roadmap 6.1)
- 오케스트레이터의 의미 검색 요구(임베딩)가 구체화되는 시점 → vector store
- 교체 비용은 GraphStore/GraphRagClient 구현체 추가로 한정되도록 경계 유지
