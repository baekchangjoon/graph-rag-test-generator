# Stage 3b — mutating by-id 생성 테스트 정합성 — 설계

작성일: 2026-06-15 (v2 — opus/sonnet/haiku 검토 반영)
관련: PR #22(by-id 진입 플러밍), [[input-discovery-staged-roadmap]]
배경 검증: petclinic by-id 생성 테스트를 fresh DB에 실행해 발견한 두 결함.

## 배경 / 문제 (실측)

PR #22로 by-id가 service에 진입하고 GET-by-id 생성 테스트는 빈 DB에서 통과한다. 그러나 라이브 실행에서
**PUT/DELETE(mutating) by-id 생성 테스트가 fresh DB에서 불일치**한다. 두 원인:

1. **상태 누적**: 한 by-id 엔드포인트 탐색에서 orchestrator가 **하나의 시드 행**에 여러 요청을 보낸다.
   PUT은 그 행을 변이(nights=30)하므로, 이후 요청은 **누적된 상태**를 본다. 기록된 `sampleResponse`가
   누적 상태를 반영(예: S200_4는 body에 nights가 없는데 응답 nights=30 — 이전 요청이 30으로 바꾼 행).
   생성 테스트는 **fresh 시드(nights=1)** + 그 요청 body라 누적값을 재현 못 함 → 응답 불일치.
2. **무의미한 어설션**: 현재 응답 단언은 대부분 `notNullValue()`. 구체값이 나와야 의미가 있다.
   단, broad `equalTo`는 **서버 생성 값**(POST 시퀀스 id, search count, timestamp)에서 깨진다
   (order-service 회귀로 확인: `id` 기대 96883 vs 실제 2, `count` 2 vs 0).

## 목표

1. **mutating by-id 요청 격리**: 탐색 중 mutating by-id의 **각 요청 전에 대상 리소스를 시드 상태로
   리셋**한다. 그러면 각 path의 기록된 응답 = f(fresh 시드, 그 요청) → 생성 테스트(fresh 시드 + 요청)가
   결정적으로 재현.
2. **결정성 인지 구체 어설션**: 응답 필드 값이 **입력/시드로 결정**되면 `equalTo(값)`, **서버 생성**
   (시퀀스 id·count·timestamp·uuid)이면 `notNullValue()`.

## 비목표

- 상태 의존 가드 **양 arm**을 여는 시드 변종(stale 과거날짜, capacity 다중행) — 별도(Stage 4).
  (갱신 2026-06-15: 저장된 단일 행 TEMPORAL/ENUM 가드의 양 arm은 Stage 4 정적 StateGuardOracle로 해결 —
  `docs/superpowers/plans/2026-06-15-stage4-state-guard-two-arm-seeds.md`. capacity 다중행은 여전히 보류.)
- 다중 행/관계형 상태(여러 리소스 조합) 시나리오.
- 비-by-id(POST 등)의 상태 격리 — POST는 리소스를 미리 시드 안 하므로 해당 없음.

## 설계

### §1. mutating by-id 요청별 시드 리셋

`EndpointExplorationRunner`:
- mutating by-id = `!GET && hasPathParam`(현 happyInput과 동일 조건) 그리고 happy.seeds() 비어있지 않음.
- `httpInvoker(endpoint)`를 감싸(데코레이트), **각 invoke 전에 시드 행들을 시드 상태로 리셋**한다.
  HeuristicExplorer/CoverageGuidedFuzzer 둘 다 이 invoker를 통해 요청하므로 한 곳 래핑으로 양쪽 적용.
- **리셋 = 전체 happy.seeds()를 reverse-order DELETE 후 다시 INSERT**(결정적, FK 안전):
  - **검토 반영(opus C1, 치명)**: `Seeds.insert`는 멱등(`ON CONFLICT DO NOTHING`/`INSERT IGNORE`,
    `SqlDialect`)이라 **변이된 행 위 재-INSERT는 no-op**(nights=30이 그대로). 따라서 **반드시 먼저
    DELETE**해야 한다. → `Seeds.delete(connection, dbType, seedRow)` **신설**(PK = columns[0] 기준
    `DELETE FROM t WHERE pk=?`), `resetSeeds(conn, dbType, seeds)` = seeds reverse로 delete 전부 →
    seeds 정순으로 insert 전부(parent-before-child 유지).
  - PUT 요청은 update로 변이하지만 다음 요청 전 리셋되므로 각 요청 독립. DELETE 요청은 행 삭제 →
    다음 요청 전 리셋이 재삽입 → 다음 요청도 fresh.
- 리셋은 **탐색 중에만**(생성 테스트는 @BeforeEach가 fresh 시드를 INSERT하므로 동일 효과). 첫 invoke
  전 리셋은 직전 seed-insert와 1회 중복이나 무해(결정적).
- **JaCoCo 무관**(opus m5): 리셋은 JDBC 직접 실행(SUT HTTP 경로 밖)이라 app 커버리지에 안 잡힘.
  요청별 `coverage.dump(true)` delta에 리셋 I/O가 섞이지 않음.
- **fuzzer 무관**(sonnet): 리셋은 DB 행만 복원, explorer의 입력(body/param) 시드 큐는 그대로 →
  변이 누적 탐색 능력 유지.

근거: mutating by-id의 각 path 기록 응답이 (fresh 시드, 요청 body)의 순수 함수 →
생성 테스트(동일 fresh 시드 + 동일 body)가 정확히 재현. 상태 누적 불일치(S200_4류) 제거.

**상태-누적 arm 트레이드오프(검토 반영)**: 리셋하면 "기존 행 status가 직전 요청으로 바뀌어야 열리는"
arm(예 canTransitionTo의 특정 전이)은 탐색에서 줄어든다. 그러나 그런 arm은 **fresh 시드로 재현 불가한
테스트**(지금 고치려는 버그)를 낳으므로, **정합성(재현 가능한 테스트)** 을 위해 트레이드한다. 양 arm을
체계적으로 여는 것은 concolic 시드 변종(Stage 4)의 몫.

### §2. 결정성 인지 구체 어설션

현재 `assertionsFromResponse`는 **SQL LITERAL 바인딩 값**만 `equalTo` 후보로 본다(by-id 응답값은 거기
없어 전부 notNull). 이를 **단일 `knownValues` 집합**으로 **통일·대체**한다(검토 반영 — 두 경로 혼재 금지):

- `knownValues` = (요청 body 필드 값) ∪ (path/query param 값) ∪ (시드 행 값) ∪ (기존 SQL LITERAL 바인딩값).
  모두 문자열로 정규화(JsonNode `asText()`, RequiredSeed.values는 이미 String).
- 각 응답 필드(키 k, 노드 v):
  - `v.asText()`가 knownValues에 있고 `!looksServerGenerated(asText)` → **`equalTo`(그 필드 자신의 값)**.
    - **검토 명확화(haiku 반려)**: 단언값은 **그 필드 v의 자기 값**이다. knownValues 멤버십은 equalTo냐
      notNull이냐를 **게이트**할 뿐 — 다른 필드 값으로 교차 오염되지 않음.
    - emit 타입(검토 명확화 — 값 비교는 문자열, emit은 노드 타입): `v.isIntegralNumber()||v.isBoolean()`
      → `equalTo(<asText>)`(따옴표 없이); `v.isTextual()` → `equalTo("<asText>")`; 그 외(실수/객체/배열)
      → `notNullValue()`(RestAssured 수치 타입 매칭 불안정 → 실수 보수적).
  - 아니면 → `notNullValue()`.
- `knownValues` 출처(검토 반영 — opus M3, offset 정합):
  - 요청 body/param: **`path.sampleInput()`**(이미 per-path offset id 반영됨)의 전 필드 값.
  - 시드 값: **`client.seedsForPath(pathId)`**(= 후처리된 perPath 시드, offset PK 포함)의 `values`.
  - 둘 다 generateSingle이 읽는 것과 동일 출처라 응답(offset id)과 어설션이 정합.
- 효과: by-id 응답 필드(시드/요청 유래: id=offset PK, nights, status, priceTier 등)는 `equalTo`,
  POST 시퀀스 id·search count(입력/시드에 없음)는 `notNullValue` → order-service 무회귀 + 의미 있는 단언.

### 배선 (검토 반영 — compose 오버로드/WS/테스트)
- §1: `EndpointExplorationRunner.run()`에서 mutating by-id면 `httpInvoker`를 리셋 데코레이터로 감쌈 +
  `Seeds.delete` 신설 + `resetSeeds(conn, dbType, happy.seeds())` 헬퍼.
- §2: `Generator.generateSingle`에서 `knownValues` 수집(sampleInput 값 + seedsForPath 값 + sql literal)
  → **5-arg `compose`에 knownValues 파라미터 추가**(`compose(path, sql, tables, seeds, readPath, knownValues)`)
  → `assertionsFromResponse(path, knownValues)`.
  - **WS 3-arg `compose`(`generateWs`, Generator:95)는 불변**(knownValues 개념 없음 → `Set.of()`로
    위임하는 내부 5-arg 오버로드 유지 또는 3-arg가 빈 knownValues로 위임).
  - **테스트 마이그레이션**: `FixtureComposerTest`의 기존 `compose(...)`/`assertionsFromResponse` 호출은
    새 파라미터(빈 또는 명시 knownValues)로 갱신. `assertions_literalEquals_othersNotNull`는 knownValues
    기반으로 의미 재정의(LITERAL 값도 knownValues에 포함되므로 기존 케이스는 유지되되, 시드/요청 값
    케이스 추가).

## 측정 / 성공 기준
1. order-service e2e 22/22 GREEN(무회귀) — 구체 어설션 변경 후 POST 시퀀스 id/search count가 notNull로
   남아 통과(knownValues에 없으므로).
2. petclinic by-id 생성 테스트를 **fresh 라이브 petclinic에 실행 → PUT/DELETE/GET 통과**. 검증 방법:
   빌더 재실행(petclinic) → test-generator로 get/put/delete-api-reservations-id 생성 →
   `.work/run-suites.sh petclinic`로 **라이브 petclinic(data.sql만 있는 fresh DB)** 에 실행 →
   실패 0(이전 측정 12 실패 기준선 대비). **login 플레이크 구분**: `login failed`(병렬 인증 경합,
   본 spec 범위 밖 인프라)는 1회 재실행으로 일시성 확인 후 제외 집계.
3. 생성된 by-id 테스트가 `equalTo` 구체값 단언 포함(예 `body("nights", equalTo(1))`,
   `body("status", equalTo("CANCELLED"))`, `body("priceTier", equalTo("VIP"))`), 서버생성/실수만 notNull.
4. 전 모듈 단위 GREEN(`FixtureComposerTest` knownValues 보강 + 기존 마이그레이션 포함).

## 테스트
- `FixtureComposerTest`(보강): knownValues에 있는 값 → equalTo, 서버생성/미지값 → notNull. 정수/문자열 타입.
- `EndpointExplorationRunner`: resetSeeds 단위(추출 가능하면) 또는 통합으로 mutating by-id 재현 확인.
- 회귀: order-service e2e 22/22, 전 모듈 단위.
- 성과: petclinic by-id 생성 테스트 라이브 실행 통과(스크립트로).

## 실측 결과 (구현 후)

- **petclinic by-id 생성 테스트 fresh 라이브 실행: 16/16 통과(이전 12 실패 → 0).** PUT/DELETE/GET
  모두 자기 리소스를 INSERT(타입 정상) → 요청 → 구체값 단언. 시드 리셋으로 mutating 누적 불일치 해소.
- 구현 중 추가 발견·수정: 쿼리 `=`가 mustache 기본 HTML-escape로 `&#61;`가 되어 DELETE `confirm`
  (기본 false)이 400나던 것 → `{{{requestPath}}}`로 수정.
- order-service e2e 22/22 GREEN(구체 어설션 무회귀 — POST 시퀀스 id/search count는 필드명-keyed라 notNull 유지).
- 전 모듈 단위 GREEN(`FixtureComposerTest` knownByField 케이스 추가).

## 위험과 완화
- **시드 리셋 비용**: 요청마다 DELETE+INSERT → by-id 예산만큼 추가 DB I/O. by-id 예산 작아 수용.
- **리셋이 FK 부모까지 지우면 무결성**: target 리소스 행만 리셋(FK 부모 유지). reverse-order DELETE면 안전.
- **구체 어설션 타입 매칭**: 실수/중첩은 notNull 유지(보수적). 정수는 응답 JsonNode 타입으로 판별.
- **login 플레이크**: 병렬 인증 경합은 별개(테스트 인프라). 본 spec 범위 밖이나, 재현 실행 시 재시도로 구분.
- **knownValues 동명 충돌**: 서로 다른 필드가 같은 값이면 과도하게 equalTo 가능 — 값 일치 기반이라
  실제 응답과도 일치하므로 통과(거짓 양성 아님).
