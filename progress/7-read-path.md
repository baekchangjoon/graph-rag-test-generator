# 7-read-path — GET read-path 시드/합성

날짜: 2026-06-14

## 진행 내용

조회 엔드포인트는 응답할 row가 DB에 있어야 테스트가 성립한다. builder가 시드를
`RequiredSeed` graph 사실로 기록하고 generator가 재현한다.

**shared-model (A1):**
- `RequiredSeed`: seedId/pathId/table/columns/values
- `ExploredPath.requiredSeedIds` 필드 추가 (없으면 빈 리스트로 역직렬화 — 구버전 호환)
- `GraphAsset.seeds` 필드 추가

**ReadInputSynthesizer (E1·C#3):**
- 타깃 테이블 결정: path 세그먼트↔테이블명 단/복수 매칭, 쿼리 파라미터 컬럼 매핑
- FK 부모 테이블을 재귀적으로 먼저 시드 (parent-first 순서)
- Postgres IDENTITY 컬럼은 명시 값 INSERT 후 `setval(seq, max_id)` 재동기
- 결정적: id=1 고정, 공유 probe FK 값 — 동일 입력에 동일 시드

**탐색 엔진 입력 일반화 (C#2):**
- `EndpointTarget`: `baseInput(JsonNode)` + `mutableFields` 분리
- GET의 path/query param을 평탄 `JsonNode {paramName:value}`로 표현
- `HeuristicExplorer`·`CoverageGuidedFuzzer`·dedup 로직 변경 없이 GET 재사용
- POST는 기존 body-centric 그대로 (byte-identical)

**generator (E4):**
- `requiredSeedIds` → FK 순서 정렬 후 `INSERT` 코드 블록 합성
- GET URL 리터럴 치환 + path param 값 삽입
- 응답 status 200 + body `isNotEmpty` 단언 합성

**샘플 SUT (F2):**
- `GET /api/orders/{id}` + `GET /api/orders?userId=` 엔드포인트 추가

## 후속 과제 (현재 한계)

- 증분 빌드 시 read-path 시드가 이월되지 않음 (`IncrementalPlan`에 `carriedSeeds` 없음).
  풀빌드는 정상. 증분 시 시드 재생성 필요 — 별도 과제
- 시드 PK id=1 고정 + 공유 probe FK → read-path 테스트 클래스 간 병렬 실행 시
  row 충돌 가능. 현재 sequential 실행으로 안전; 격리 강화는 보류
- 타깃 테이블 결정은 path/스키마 휴리스틱. codegraph handler→repository→entity
  call-graph 추적으로 정확도를 높일 수 있으나 현재 불필요

## 검수

- `ReadInputSynthesizer` 단위 GREEN
- `ExploredPath`/`GraphAsset` JSON 라운드트립 GREEN
- e2e: GET-by-id 3건 + GET-by-userId 3건 통과 (7-e2e.md 참조)
