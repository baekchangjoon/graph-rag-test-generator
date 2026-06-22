# 배열/중첩 바디 변이 + 다중 필드 조인 가드 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-22-array-nested-mutation-joinguard-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용
> 테스트를 가짐 (대상 매트릭스 전부 green) + 기존 전 모듈 회귀 GREEN.
> 리뷰: requirements 전용 design-doc 리뷰(Sonnet) approved_with_conditions 6건 반영본.

## 신규 픽스처 (구현 선행 — 테스트 컴파일 전제)
- **ShipRequest 핸들러** (REQ-003/004): `samples/order-service`에 `POST /api/orders/ship` +
  `record ShipRequest(String userId, Address address)`(기존 `Address` POJO) + 존재/blank 가드.
- **StringJoinController** (REQ-008a/008b): `graph-rag-builder/src/test/resources/sample-src/
  io/graphrag/sample/bounds/StringJoinController.java` — `String a, b`를 `a.equals(b)`로 비교하는
  메서드(양변 field-ref, 리터럴 없음). 현재 `sample-src`에 field-to-field `equals` 픽스처 없음.

## 요구사항 목록

### REQ-001 — 최상위 List<DTO> 배열 바디 원소 필드 변이
- 유형: Functional · 우선순위: Must
- 설명: 최상위 컬렉션 바디(`List/Set/Collection/Iterable<DTO>`, `DTO[]`)의 **원소(element[0])
  필드**에 기존 변이가 적용된다.
- 수용기준:
  - Given `OrderBatchController.batch`(`@RequestBody List<OrderController.CreateOrderRequest>`)를
    탐색하면, When `null-userId`/`zero-amount` 등 원소 필드 변이가 적용될 때, Then 그 입력이
    happy(2xx) 외의 **신규 분기/응답차**(`continue` arm으로 `created` 변화)를 만든다.
- 검증 레벨: E2E black-box

### REQ-002 — 빈 배열 구조 변이
- 유형: Functional · 우선순위: Must
- 설명: 컬렉션 바디 탐색 시 빈 배열 `[]` 구조 변이를 1종 시도한다.
- 수용기준:
  - Given 컬렉션 바디 엔드포인트를 `HeuristicExplorer`로 탐색하면, When 변이 집합을 산출할 때,
    Then 입력 `[]`를 시도한 ExploredInput이 결과에 포함된다.
- 검증 레벨: integration (explorer 단위)

### REQ-003 — 중첩 객체 필드 dot-path 변이로 가드 arm 도달
- 유형: Functional · 우선순위: Must
- 설명: 중첩 객체 필드(`address.city`)에 dot-path 변이(`null-…`/`remove-…`)가 적용되어 SUT의
  중첩 필드 가드 분기에 도달한다.
- 수용기준:
  - Given nested JSON 핸들러 `POST /api/orders/ship`(**신규 추가 예정 — design spec AC-2**;
    `ShipRequest{userId, Address address}`, 가드 `address==null || address.city==null || blank → 400`)를
    탐색하면, When `null-address.city`/`remove-address.city` 변이가 적용될 때, Then 400 arm을 커버한다.
- 검증 레벨: E2E black-box

### REQ-004 — 중첩 바디 happy 합성의 구조 정확성
- 유형: Functional · 우선순위: Must
- 설명: 중첩 DTO 바디의 happy 입력이 평면 키가 아닌 **중첩 JSON**으로 합성된다.
- 수용기준:
  - Given `ShipRequest{userId, Address address}` 바디(**신규 추가 예정 — design spec AC-2**)를,
    When happy 입력을 합성하면, Then `{"address":{"city":…}}` 중첩 JSON이 생성되고 2xx로 통과한다
    (평면 키 `{"address.city":…}` 아님).
- 검증 레벨: E2E black-box

### REQ-005 — BodyShapeExtractor 재귀 dot-path 평탄화(깊이·cycle)
- 유형: Functional · 우선순위: Must
- 설명: `BodyShapeExtractor.extractFromTypeFlattened`(JSON `@RequestBody` 전용 변형; `extract()`는
  un-flattened 유지 — 역전파 v3)가 중첩 DTO 컴포넌트를 dot-path 스칼라 리프로 재귀 전개하되, 깊이
  상한(3)과 **경로별** cycle guard를 적용한다. `extract()`는 form 분류 계약상 평탄화하지 않는다.
- 수용기준:
  - Given 중첩/순환 DTO 타입을, When `extract`하면, Then (a) `address.city` dot-path 리프 산출,
    (b) 깊이 3 초과 경로 절단, (c) 순환 타입 경로별 차단, (d) 같은 타입 형제 필드 둘 다 전개.
- 검증 레벨: unit

### REQ-006 — field-to-field 숫자 조인 가드 추출
- 유형: Functional · 우선순위: Must
- 설명: 양변이 모두 field-ref인 관계 가드(`a OP b`)를 `JoinGuard`로 추출한다(추가 파싱 없이 기존
  비교식 순회 재사용).
- 수용기준:
  - Given `BoundsController`의 `if (req.amount() > req.score())`를, When 비교식을 추출하면,
    Then `JoinGuard(NUMERIC, leftRef="amount", op=">", rightRef="score")`가 산출된다(현재 "무시" 아님).
- 검증 레벨: unit

### REQ-007 — 숫자 조인 가드 변이 합성(3-arm)
- 유형: Functional · 우선순위: Must
- 설명: 두 필드가 모두 mutable일 때, 숫자 조인 가드당 `a<b`/`a==b`/`a>b` 동시세팅 변이 3종을
  합성한다(연산자 무관 양 arm 커버).
- 수용기준:
  - Given `JoinGuard(NUMERIC, amount, >, score)` + `mutableFields ⊇ {amount, score}`를, When
    `joinGuards` 호출 시, Then `{amount<score}`·`{amount==score}`·`{amount>score}` 세 atomic 변이가
    산출된다(각 변이가 두 필드 동시 세팅).
- 검증 레벨: unit

### REQ-008a — 문자열 equals 조인 가드 추출
- 유형: Functional · 우선순위: Must
- 설명: `a.equals(b)`(양변 field-ref, 리터럴 아님)를 `JoinGuard(STRING)`으로 추출한다. 리터럴 인자는
  기존 STRING_EQ가 처리하므로 제외.
- 수용기준:
  - Given `StringJoinController`의 `a.equals(b)`(신규 픽스처)를, When 추출하면, Then
    `JoinGuard(STRING, leftRef="a", op="equals", rightRef="b")`가 산출된다.
- 검증 레벨: unit

### REQ-008b — 문자열 조인 가드 변이 합성(2-arm)
- 유형: Functional · 우선순위: Must
- 설명: 문자열 조인 가드당 `a==b`/`a≠b` 변이 2종을 합성한다.
- 수용기준:
  - Given `JoinGuard(STRING, a, equals, b)` + `mutableFields ⊇ {a, b}`를, When `joinGuards` 호출 시,
    Then `{a="x",b="x"}`·`{a="x",b="y"}` 두 atomic 변이가 산출된다.
- 검증 레벨: unit

### REQ-009 — path-aware put의 non-object 중간노드 안전성
- 유형: Non-functional (robustness) · 우선순위: Must
- 설명: `putPath`는 중간 세그먼트가 없거나 non-Object(NullNode/스칼라/Array)여도 예외 없이 새
  ObjectNode로 교체하고 리프를 세팅한다. `removePath`는 리프만 제거(빈 부모 유지).
- 수용기준:
  - Given `{"address":null}`과 경로 `address.city`를, When `putPath(root,"address.city","x")`,
    Then 예외 없이 `{"address":{"city":"x"}}`. And `removePath` 후 `{"address":{}}`.
  - Given 점 없는 이름 `userId`를, When `putPath`, Then `root.put("userId",…)`와 동일.
- 검증 레벨: unit

### REQ-010 — 평면 객체 바디 탐색 회귀 불변
- 유형: Non-functional (regression) · 우선순위: Must
- 설명: 본 변경이 기존 평면 객체 바디 엔드포인트의 탐색·테스트 산출을 축소하지 않는다.
- 수용기준:
  - Given 기존 `e2e` 전체를 실행하면, Then 모든 기존 생성 테스트가 GREEN이고, 생성 테스트 수가
    변경 전과 **같거나 많다**(축소 없음). And 전 모듈 단위/통합 GREEN.
- 검증 레벨: E2E black-box / regression

### REQ-011 — JSON happy 바디 중첩(runner nestDottedKeys) + FK 휴리스틱 carve-out
- 유형: Functional · 우선순위: Must
- 설명(역전파 v3): `SampleInputSynthesizer`는 리터럴 키 유지하되 FK 휴리스틱은 점 포함 이름에 미적용.
  JSON happy 바디 중첩은 `JsonPaths.nestDottedKeys`(순수 변환)로 처리하고, `run()`이 non-form JSON
  object 바디에만 적용한다.
- 수용기준:
  - Given `{"address.city":"x","shipTo.userId":"y"}` object를, When `JsonPaths.nestDottedKeys`,
    Then `{"address":{"city":"x"},"shipTo":{"userId":"y"}}`가 된다(점 없는 키는 불변).
  - Given dot-path 필드를 가진 shape를, When `SampleInputSynthesizer.synthesize`, Then `shipTo.userId`에
    대해 FK probe row가 생성되지 않는다(carve-out).
- 검증 레벨: unit

## 추적 매트릭스

| REQ-ID  | 요구사항 | 수용 테스트 | Level | Status |
|---------|----------|-------------|-------|--------|
| REQ-001  | 배열 원소 필드 변이 | `OrderBatchArrayMutationE2E#elementFieldMutationOpensBranch` | E2E | 🔴 planned |
| REQ-002  | 빈 배열 구조 변이 | `HeuristicExplorerTest#emptyArrayVariantForCollectionBody` | integration | 🔴 planned |
| REQ-003  | 중첩 dot-path 변이 가드 도달 | `NestedBodyMutationE2E#nullNestedFieldOpens400` | E2E | 🔴 planned |
| REQ-004  | 중첩 happy 합성 구조 | `NestedBodyMutationE2E#happyBodyIsNestedJson` | E2E | 🔴 planned |
| REQ-005  | BodyShapeExtractor 평탄화 | `BodyShapeExtractorNestedTest#nestedField_flattensToDotPath` | unit | 🔴 planned |
| REQ-005  | (depth cap) | `BodyShapeExtractorNestedTest#nestedDepth_cappedAtMax` | unit | 🔴 planned |
| REQ-005  | (per-path cycle) | `BodyShapeExtractorNestedTest#cyclicNested_perPathGuard` | unit | 🔴 planned |
| REQ-005  | (sibling same type) | `BodyShapeExtractorNestedTest#siblingSameType_bothExpanded` | unit | 🔴 planned |
| REQ-006  | 숫자 조인 가드 추출 | `ConstraintExtractorJoinGuardTest#fieldToFieldNumericExtracted` | unit | 🔴 planned |
| REQ-007  | 숫자 조인 가드 변이 3-arm | `InputMutatorTest#joinGuards_numericEmitsThreeArms` | unit | 🔴 planned |
| REQ-008a | 문자열 조인 가드 추출 | `ConstraintExtractorJoinGuardTest#equalsFieldToFieldExtracted` | unit | 🔴 planned |
| REQ-008b | 문자열 조인 가드 변이 2-arm | `InputMutatorTest#joinGuards_stringEmitsTwoArms` | unit | 🔴 planned |
| REQ-009  | putPath 안전성 | `InputMutatorPathTest#putPathMaterializesNonObject` / `#removePathLeafOnly` / `#flatNameUnchanged` | unit | 🔴 planned |
| REQ-010  | 평면 바디 회귀 불변 | 기존 `e2e` 전체 GREEN + 생성 테스트 수 비축소 | E2E/regression | 🔴 planned |
| REQ-011  | synthesizer dot-path + FK carve-out | `SampleInputSynthesizerNestedTest#nestedHappyAndFkCarveOut` | unit | 🔴 planned |

Coverage: 0/12 green (0%) — target 100% (대상: Must 12 + 미연기 Should 0). 제외: 없음.
(REQ-005는 4개 메서드로 검증되나 1개 REQ로 집계; 전 메서드 green 시 green.)

## 커버리지 규칙
- 분모 = Must(12). Could/Won't/연기 없음.
- 각 E2E/수용 테스트는 `@DisplayName("REQ-00X: …")`로 REQ-ID를 참조한다.
- 이중루프: REQ-001/003/004/010의 E2E(외부 루프)를 먼저 🔴→🟡, 내부 단위 TDD로
  REQ-002/005/006/007/008a/008b/009/011을 드라이브해 🟡→🟢. PR 전 매트릭스 100% green + 테스트명 대조.

## 자기검토
1. 고아 행위 없음 — design AC-1~4 + 계약(putPath 안전성·synthesizer dot-path·회귀)이 모두 REQ로 매핑.
2. 원자성 — 추출(006/008a)과 변이 합성(007/008b) 분리; happy 합성(004/011)과 변이 도달(003) 분리.
3. 수용기준 완비 — 전 REQ에 Given-When-Then + 측정 가능(REQ-010 count 기준으로 정량화).
4. 커버리지 규칙 — 분모(12)·제외(없음) 명시.
