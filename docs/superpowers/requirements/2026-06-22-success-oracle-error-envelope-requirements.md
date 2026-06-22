# 성공 오라클 + 에러 엔벨로프 대응 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-22-success-oracle-error-envelope-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 요구사항 목록

### REQ-001 — 기본 분류기는 status-only (후방호환)
- 유형: Functional
- 우선순위: Must
- 설명: classifier 설정 미지정 시 `StatusOnlyClassifier`가 적용되어 기존 동작(`wireStatus/100==2 → SUCCESS`)과 동일하다.
- 수용기준:
  - Given classifier 설정 없는 빌드, When 비-엔벨로프 SUT를 탐색, Then 2xx 응답은 SUCCESS로 분류되고 기존 graph.json·테스트 산출이 변하지 않는다.
- 검증 레벨: E2E black-box (기존 order-service/gateway/legacy-tram 회귀)

### REQ-002 — 엔벨로프 응답을 FAILURE로 분류
- 유형: Functional
- 우선순위: Must
- 설명: `--error-when-present` 설정 시, 트리거 필드가 **존재 AND non-null AND non-empty**이면 와이어 status가 200이어도 outcome=FAILURE로 분류한다(다중 필드 OR).
- 수용기준:
  - Given `--error-when-present errorCode`로 구성, When SUT가 `200 + {errorCode:"404", ...}`를 반환, Then 해당 path의 outcome=FAILURE.
  - Given 성공 응답이 `errorCode: null`을 포함, When 분류, Then outcome=SUCCESS(non-null 술어).
- 검증 레벨: E2E black-box (error-envelope SUT)

### REQ-003 — semanticStatus 복원 (원본 타입 보존)
- 유형: Functional
- 우선순위: Must
- 설명: 엔벨로프 FAILURE 시 status 필드(기본 `errorCode`)에서 의미상 status를 복원하되, 원본 JSON 타입(문자열 `"404"`)을 `semanticStatusText`로 보존한다. int 파싱 실패 시 `semanticStatus=wireStatus`.
- 수용기준:
  - Given `errorCode:"404"`, When 분류, Then `semanticStatusText=="404"` 이고 `semanticStatus==404`.
  - Given `errorCode:"X"`(파싱 불가), When 분류, Then `semanticStatus==wireStatus`이고 `semanticStatusText` 원본 보존.
- 검증 레벨: integration (ResponseClassifier 단위)

### REQ-004 — 와이어 status 보존
- 유형: Functional
- 우선순위: Must
- 설명: 재분류된 FAILURE path도 `ExploredPath.expectedStatus`는 와이어 값(200)을 유지하며, outcome/semanticStatus는 추가 필드로 기록된다(status 위조 금지).
- 수용기준:
  - Given enveloped-200 path, When graph.json 저장, Then `expectedStatus==200` AND `outcome==FAILURE` AND `semanticStatusText=="404"`.
- 검증 레벨: integration (graph.json 검사)

### REQ-005 — 성공/실패 판정의 파이프라인 일관 적용
- 유형: Functional
- 우선순위: Must
- 설명: 빌더의 success-vs-failure 판정 지점(시드 우선순위·시드 부착·Kafka happy·`lookupSucceeded`·`postCreateCleanup`·dedup/path-id·non-2xx 필터)이 모두 raw status 대신 outcome 기준으로 동작한다.
- 수용기준:
  - Given enveloped-200 POST path, When generator가 생성, Then `postCreateCleanup` 로직이 주입되지 않는다(outcome=FAILURE 게이트).
  - Given enveloped-200 GET-by-id path, When fixture 합성, Then `lookupSucceeded`가 false로 판정되어 잘못된 seed INSERT가 생성되지 않는다.
- 검증 레벨: integration (generator 산출 검사)

### REQ-006 — 에러 계약 path의 강한 단언 생성
- 유형: Functional
- 우선순위: Must
- 설명: outcome=FAILURE path에 대해 generator는 `.statusCode(200)` + `.body("<statusField>", equalTo("<semanticStatusText>"))`(문자열 매칭) + (설정 시) `errorDetail` `containsString` 단언을 생성한다. 에러 path를 notNullValue-only로 두지 않는다.
- 수용기준:
  - Given enveloped-200(`errorCode:"404"`, `errorDetail` 에 "BizException"), `--error-detail-field errorDetail --error-detail-contains BizException` 설정, When generator 생성, Then 테스트에 `.statusCode(200)`, `.body("errorCode", equalTo("404"))`, `.body("errorDetail", org.hamcrest.Matchers.containsString("BizException"))`가 포함되고 notNullValue-only 아님.
- 검증 레벨: E2E black-box (생성 테스트 내용 검사 + 컴파일·실행)

### REQ-007 — 에러 계약 path의 필터 정책
- 유형: Functional
- 우선순위: Should
- 설명: outcome=FAILURE & 와이어-2xx path는 `discoveredBy="error-envelope"` 마커로 표시되어 `verifyAndFilterNonTwoxx`에서 재현 검증 면제 KEEP(negative-* 와 동일)된다.
- 수용기준:
  - Given enveloped-200 path, When non-2xx 필터 적용, Then path가 DROP되지 않고 `discoveredBy=="error-envelope"`로 보존된다.
- 검증 레벨: integration

### REQ-008 — happy 미도달 보고
- 유형: Functional
- 우선순위: Should
- 설명: 한 엔드포인트의 모든 응답이 엔벨로프-실패면 `ExplorationReport`에 `noHappyPathReason="all responses error-enveloped"`를 기록한다.
- 수용기준:
  - Given 유효 입력을 못 만들어 모든 응답이 enveloped-200, When 탐색 종료, Then report에 해당 사유가 기록된다.
- 검증 레벨: integration (report 검사)

### REQ-009 — FAILURE 피드백 기반 유효입력 재탐색 (루프)
- 유형: Functional
- 우선순위: Must
- 설명: GET-by-id에서 outcome=FAILURE면 탐색이 종료되지 않고 pass-2 SQL hint 재시드를 재시도 예산(N=4) 내 강제한다.
- 수용기준:
  - Given enveloped-200 GET-by-id, When 탐색, Then pass-2 재시드가 예산 내 시도되고 첫 FAILURE에서 멈추지 않는다.
- 검증 레벨: integration

### REQ-010 — genuine SUCCESS path 도달 + 커버리지 상승
- 유형: Functional
- 우선순위: Must
- 설명: error-envelope SUT의 GET-by-id에 유효 id 시드 시, 예산 내 genuine SUCCESS(비-엔벨로프) path ≥ 1 도달하고 해당 엔드포인트 branch coverage가 RC-A 미적용 대비 상승한다.
- 수용기준:
  - Given `items`에 유효 id 시드, When error-envelope SUT를 탐색, Then genuine SUCCESS path ≥ 1 + 해당 엔드포인트 branch coverage > 베이스라인.
- 검증 레벨: E2E black-box (error-envelope SUT)

### REQ-011 — classifier 설정 표면 (CLI)
- 유형: Functional
- 우선순위: Must
- 설명: `--error-when-present`, `--semantic-status-field`(기본 errorCode), `--error-detail-field`, `--error-detail-contains` CLI 플래그가 `BuildConfig.classifierConfig`로 주입되며 attach·incremental 모드 모두 미지정 시 status-only.
- 수용기준:
  - Given 플래그 미지정, When 빌드, Then status-only(REQ-001과 동치).
  - Given 플래그 지정, When 빌드, Then ErrorEnvelopeClassifier가 그 설정으로 동작.
- 검증 레벨: integration (CLI 파싱 + 주입)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | 기본 status-only 후방호환 | ExistingRegression(order/gateway/tram) GREEN 유지 | E2E | 🔴 planned |
| REQ-002 | 엔벨로프→FAILURE 분류 | ErrorEnvelopeClassifierTest#enveloped200IsFailure / E2E AC1 | E2E | 🔴 planned |
| REQ-003 | semanticStatus 복원·타입 보존 | ErrorEnvelopeClassifierTest#recoversSemanticStatusText | integration | 🔴 planned |
| REQ-004 | 와이어 status 보존 | GraphAssetOutcomeTest#wireStatusPreserved | integration | 🔴 planned |
| REQ-005 | 파이프라인 일관 적용 | PostCreateCleanupGatedTest / LookupSucceededOutcomeTest | integration | 🔴 planned |
| REQ-006 | 에러 계약 강한 단언 | ErrorContractAssertionE2E (AC2) | E2E | 🔴 planned |
| REQ-007 | 에러 path 필터 정책 | VerifyAndFilterEnvelopeKeepTest | integration | 🔴 planned |
| REQ-008 | happy 미도달 보고 | NoHappyPathReportTest | integration | 🔴 planned |
| REQ-009 | FAILURE 피드백 재탐색 | RcbRetryLoopTest (AC3a) | integration | 🔴 planned |
| REQ-010 | genuine SUCCESS 도달 + 커버리지 | ErrorEnvelopeSutE2E#reachesSuccess (AC3b) | E2E | 🔴 planned |
| REQ-011 | classifier CLI 설정 표면 | BuilderCliClassifierConfigTest | integration | 🔴 planned |

Coverage: 0/11 green (0%) — target 100% (대상: Must 9 + Should 2; 미연기 Should 전부 포함). Could/Won't 없음.
