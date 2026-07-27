cand-01 (Task 18, REQ-018/REQ-036 부트스트랩): 사람 갭필 승격 — spec §10 허용.

## 출처

`synthesize-triple --report graph-rag-builder/src/test/resources/golden/provenance-post-api-transfers.json
--triple-store <tmp>` 로 CLI 자동 산출을 먼저 확인했다. CLI(tables=[] 최소 배선)는 다음까지만
자동 산출한다:

- `amount=100` / seed `fund_accounts.balance_amount=100` — 비교 가드(TransferController.java:30,
  `balance < amount` 부정 관계 GE) 자동 co-location. (변경 없음, 그대로 채택)
- stub `POST /fraud/check -> {"status":"CLEAR"}` — 부정 등가 가드(TransferController.java:37) 자동
  라우팅. (변경 없음, 그대로 채택)
- `note` 갭 마커 — unguarded free-text 필드. (사람이 값만 채움 — 브리프 예시와 동일)
- `items.sku` 갭 마커이나 **object**(`{"sku": marker}`)로 배치 — `JsonPaths.putPath`가 배열을 모르므로.

## 사람 갭필로 확장한 부분(자동 산출 갭)

1. **fromAccountId**: EXISTS 가드(TransferController.java:28)는 CLI가 `tables=[]`로 호출돼 대상
   테이블/PK를 못 찾아 skip한다(notes: "대상 테이블/PK 미해결"). 실제 물리 스키마(fund_accounts,
   PK=id)를 알면 `TripleSynthesizer.deterministicIdValue("fromAccountId", "String", pk)`가
   결정적으로 `"seed-fromaccountid"`를 산출한다(비수치 jsonPath → `"seed-" + normalize(jsonPath)`
   공식, TripleSynthesizer.java 참고) — 이 값을 그대로 사람이 채워 넣었다(마커 아님, 결정 가능한
   값이므로 base/promoted 동일).
2. **items(중첩 리스트)**: 컨트롤러의 `items`는 `List<TransferItem>`(sku:String, qty:int)이다.
   `BodyShapeExtractor.flatten()`은 컬렉션 필드를 원소 DTO까지 전개하지 않고 `items` 하나만
   top-level 리프로 담으며(`TripleSynthesizer` Javadoc "남은 확장 지점"), `TripleSynthesizer`의
   `||` 결합 가드 라우팅도 미지원(default 분기)이라 `items.qty`는 애초에 어떤 값도 배치하지 못한다.
   두 갭 모두 이 task의 "선결 문제"(REQ-011 스키마 게이트, TripleValidator.schemaViolationsForBody)
   와는 별개의 TripleSynthesizer/BodyShapeExtractor 자동화 갭(Task 9+ 백로그, 별도 REQ 없음)이므로,
   이 후보는 spec §10이 허용하는 "사람 갭필 부트스트랩"으로 `items`를 배열-of-객체
   `[{"sku": 마커, "qty": 마커}]`로 직접 구성했다. **마커 위치(REQ-009)는 배열 원소 내부의
   스칼라(sku/qty)에만 있다 — 배열 자체의 존재/크기는 base/promoted 동일(구조 불변, 값만 채움)이므로
   마커 계약(REQ-009/011, "마커는 값 치환이지 구조 대체가 아니다")을 위반하지 않는다.**
3. **T1 게이트 정합성(REQ-011 보강)**: 위 items 배열이 `TripleValidator.schemaViolationsForBody`를
   통과하려면 `BodyShape.fields()`(top-level `items` 하나만 있음)에 대해 `items.sku`/`items.qty`
   dot-path 리프를 허용해야 한다 — 이 task가 먼저 고친 선결 갭(allowed를 dot-path 접두사 매칭으로
   확장, `TripleValidator.isAllowedPath`)이 정확히 이 승격을 가능하게 한다. 완전히 새로운 top-level
   필드(예: `hacked.x`)는 여전히 reject됨(회귀 테스트
   `TripleGateIT#req011_unknownTopLevelPrefixStillRejectedEvenIfNested`).

4. **stub.response.headers(Content-Type) — 완주 E2E로 실측한 추가 갭**: `TripleSynthesizer.
   routeNegatedEqualityGuard`가 산출하는 stub은 `{"status","jsonBody"}`만 채우고 헤더를 넣지 않는다.
   WireMock은 `jsonBody`만 있는 mapping에 Content-Type을 자동으로 붙이지 않는다(재현:
   `ScratchStubReproTest`류 최소 재현으로 응답 헤더가 `matched-stub-id`/`transfer-encoding`뿐임을
   확인, 재현 코드는 진단 후 삭제) — `FraudClient`의 `RestTemplate.postForObject(..., FraudResult.class)`가
   Content-Type 부재로 메시지 컨버터를 못 찾아 예외를 던지고, `TransferController`가 이를 잡지 않아
   SUT가 500을 반환한다(완주 E2E 1차 시도에서 `trial 재확인 실패(REQ-020): status=500`으로 실측).
   이 후보는 `stub.response.headers.Content-Type=application/json`을 명시적으로 채워 회피했다 —
   `TripleValidator.STUB_RESPONSE_KEYS`에 `headers`를 추가해 T1 스키마 게이트가 이를 허용하도록
   보강했다(REQ-011, 회귀 테스트 `TripleGateIT#req011_stubResponseHeadersKeyAccepted`). **미해결로
   남기는 부분:** `TripleSynthesizer`가 EXTERNAL_RESPONSE stub을 자동 생성할 때 Content-Type을
   자동으로 채우지 않는 것 자체는 이 task의 선언 파일 범위(e2e fixture + `TriplePromotionE2E`) 밖이라
   고치지 않았다 — 향후 자동 생성 stub도 동일한 500 함정에 빠질 수 있으므로 별도 후속 task 필요
   (새 REQ-ID 없이 REQ-008/011 백로그로 기록).

5. **amount/balance = 1 — shell e2e(run-e2e.sh + test-generator)로 실측한 3번째 갭.** 처음엔
   `amount=100`/seed `balance_amount=100`(TripleSynthesizer의 NUMERIC_ANCHOR 관례)이었다. 이 값으로
   in-process `TriplePromotionE2E`(Testcontainers, 그래프 빌드 자체)는 GREEN이었지만,
   `e2e/run-e2e.sh`가 생성한 `TransfersPostTest.s201_1`(실 docker-compose 스택)은 **422**로 실패했다.
   원인: test-generator/탐색기의 "`xxxId` 필드 → FK 부모 행 자동 시드" 관례
   (`SampleInputSynthesizer.findFkTarget`/`defaultFor`)가 이 승격 후보의 `seed.sql`(`balance_amount=100`)을
   전혀 참조하지 않고, **NOT NULL numeric 컬럼 제네릭 기본값(=1)으로 매 시나리오마다 별도 seed 행을
   새로 만든다**(생성 테스트의 `scope.jdbc().update(...INSERT..., 1, fromAccountId)`가 그 증거) —
   즉 `body.amount`가 실제로 만족해야 하는 것은 candidate seed.sql의 값이 아니라 **이 제네릭
   기본값(1)**이다. `amount`를 `1`로(그리고 seed.sql의 `balance_amount`도 결정값 정합을 위해 `1`로)
   낮춰 `balance(1) < amount(1)` → false를 만족시켜 회피했다.
6. `req.items()` 비어있지 않음, `items.get(0).qty()(2) <= 0` → false → 422(invalid items) 회피.
7. `fraudClient.check(...)` → stub 응답 `{"status":"CLEAR"}` → `"CLEAR".equals("CLEAR")` → 409 회피.
8. 201 CREATED, body `{"id":"TRF-seed-fromaccountid","note":"promoted candidate note"}`.

## 컨트롤러 분기 통과 확인(수동 트레이스, 최신 값 기준: amount=1/balance=1)

1. `accountRepository.findById("seed-fromaccountid")` → seed 행 존재(위 seed.sql) → 404 회피.
2. `account.getBalance()(1) < req.amount()(1)` → false → 422(잔액부족) 회피.
3. `req.items()` 비어있지 않음, `items.get(0).qty()(2) <= 0` → false → 422(invalid items) 회피.
4. `fraudClient.check(...)` → stub 응답 `{"status":"CLEAR"}` → `"CLEAR".equals("CLEAR")` → 409 회피.

## 별도로 발견했으나 이 task 범위 밖으로 남기는 갭(shell e2e 실측, 새 REQ-ID 없음)

`e2e/run-e2e.sh` 실행에서 `TransfersPostTest`의 negative-validation 파생 시나리오 2건
(`s422e422_1`: amount를 base(=1) 대비 +1 경계인 `2`로 mutate해 "잔액부족 422"를 노리는 변이,
`s422e422_2`: `items` 필드를 통째로 drop해 "invalid items 422"를 노리는 변이)이 **404**로 실패했다 —
두 시나리오 모두 생성 코드에 `scope.jdbc().update(...)` seed 삽입 자체가 없다(`fromAccountId`가
`scope.testId()` 기반 매 테스트 고유 id라 사전 시드 없이는 항상 계정 미존재 → 404). 이는
`EndpointExplorationRunner`의 negative-validation/mutation 패스가 "제약 위반 변이"를 만들 때 FK
존재-가드에 필요한 시드 요구사항을 같이 추적하지 못하는(또는 test-generator의 per-test id 격리와 그
요구사항이 어긋나는) 구조적 갭이다 — 원인 코드 경로 자체는 이 task 이전부터 존재했지만,
`post-api-transfers`가 이 task 이전에는 한 번도 2xx에 도달한 적이 없어(REQ-028 outer red) 이
negative-validation 상호작용이 지금까지 한 번도 실제 generate+run 사이클로 노출된 적이 없었다 — 즉
"이미 실패하던 테스트"가 아니라 이 task가 처음 노출시킨 기존 서브시스템 결함이다. 이 candidate의
값 조정만으로는 고칠 수 없다(어떤 amount/seed 값을 쓰든 계정 자체가 없으므로 404가 우선 발생).
`EndpointExplorationRunner`/`test-generator`는 이 task의 선언 파일 범위 밖이라 여기서 고치지
않았다 — **REQ-037**(신규, 🔵 out-of-scope, Phase A 분모 제외)로 추적한다.

## 동반 아티팩트

`provenance-report.json`은 golden 픽스처(`graph-rag-builder/src/test/resources/golden/
provenance-post-api-transfers.json`)를 기반으로 하되, `unguarded`에 `items.qty`(위 2번 갭) 항목을
추가해 실제 자동화 상태(둘 다 미결정)와 일치시켰다 — 단위테스트 golden 픽스처 원본은 별도이며
회귀 방지를 위해 수정하지 않았다.
