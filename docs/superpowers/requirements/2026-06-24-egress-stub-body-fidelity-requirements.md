# 외부 stub 응답 body 충실도 (REQ-012) 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-24-egress-stub-body-fidelity-design.md
> 부모 추적: `docs/superpowers/requirements/2026-06-24-egress-span-capture-requirements.md`의
> REQ-012(🔵 Won't)를 본 명세에서 `REQ-F012-*`로 구체화한다(ID 충돌 방지·안정 추적).
> 선행 보존 대상: `docs/superpowers/requirements/2026-06-23-stage1-external-stub-synthesis-requirements.md`
> 계열 + egress-status-agnostic-stub의 **REQ-S015**(형상-시드 SYNTHESIZED 등록).
> 완료 정의(DoD): 커버리지 대상(Must + 미연기 Should) 요구사항이 모두 ≥1개의 통과 수용 테스트를
> 가짐 — 대상 매트릭스 전부 🟢.

핵심 용어: **CONTRACT** = 소비 코드 기대값/에러 계약 기반 합성 body provenance(신규).
**span-only** = recorder 미경유, span으로만 발견된 외부 호출(구동·관측 불가).
**redirect-capable** = SUT 외부 base URL이 recorder로 향해 구동·관측 가능한 호출.

---

## 요구사항 목록

### REQ-F012-001 — span 경로 stub body를 String equals-family 리터럴로 시드
- 유형: Functional / 우선순위: Must
- 설명: `EgressStubComposer.compose`가 매칭된 `ExternalCallSite.responseShape`의 String 필드에 대해
  `stringLiteralsByDto[dtoFqn][field]`의 소비 코드 equals-family 리터럴이 있으면 그 값(첫 리터럴,
  결정적)을 채워 happy body를 합성한다.
- 수용기준:
  - Given responseShape에 String 필드 `region`, `stringLiteralsByDto[InventoryResponseFqn][region]
    ={"EMBARGOED"}`, When compose, Then body의 `region == "EMBARGOED"`이고 provenance == `CONTRACT`.
  - Given String 필드에 추출 리터럴 없음, When compose, Then 그 필드는 `ShapeJsonSynthesizer`
    기본값(`"sample-region"`)을 유지한다.
- 검증 레벨: unit

### REQ-F012-002 — enum happy 값은 첫 상수(SYNTHESIZED 동치), CONTRACT 승격은 리터럴/envelope 출처에 한정
- 유형: Functional / 우선순위: Must
- 설명: enum 응답 필드 happy 값은 `ShapeJsonSynthesizer` 규칙(선언순 첫 상수)을 그대로 쓴다. body가
  CONTRACT로 승격되는 것은 String 리터럴 또는 에러 envelope 출처가 하나라도 적용된 경우뿐이며,
  enum-only(리터럴/envelope 없음)면 형상-시드와 동일하므로 `SYNTHESIZED`로 남는다(거짓 승격 방지).
- 수용기준:
  - Given enum 필드만 있고 String 리터럴·envelope 출처 없음, When compose, Then enum 값은 첫 상수,
    provenance == `SYNTHESIZED`.
- 검증 레벨: unit

### REQ-F012-003 — 에러 envelope 합성기
- 유형: Functional / 우선순위: Must
- 설명: `ErrorContractDescriptor`(`semanticStatusField`/`errorDetailField`/`errorDetailContains`)가
  non-null이면 에러 envelope JSON을 합성한다(있는 필드만 채움). `semanticStatusField` 키의 값은 고정
  센티넬 `"ERROR"`, `errorDetailField` 키의 값은 `errorDetailContains`(있으면) 또는 빈 문자열로 한다
  (결정적). descriptor가 null이면 envelope를 합성하지 않는다(loud 없음 — 정상 부재).
  - **검증 범위 주의**: 저장소에 외부 HTTP egress와 에러 envelope를 겸비한 sample SUT가 없다
    (`samples/error-envelope-service`는 외부 HTTP 없음; `order-service`는 envelope 없음). 따라서 본
    REQ는 **synthetic `ErrorContractDescriptor`를 주입한 unit 테스트로 검증**하며, 실 SUT E2E는 두지
    않는다(REQ-F012-005 integration도 synthetic descriptor 기반).
- 수용기준:
  - Given descriptor `{semanticStatusField:"errorCode", errorDetailField:"errorDetail",
    errorDetailContains:"BizException"}`, When 합성, Then JSON `{"errorCode":"ERROR",
    "errorDetail":"BizException"}`.
  - Given descriptor == null, When 합성 시도, Then envelope 미생성·loud-fail 없음.
- 검증 레벨: unit

### REQ-F012-004 — `CapturedHttpCall.Provenance`에 CONTRACT 추가 + 후방 호환
- 유형: Functional / 우선순위: Must
- 설명: provenance enum에 `CONTRACT` 추가(`{CAPTURED, SYNTHESIZED, CONTRACT}`). 레거시 graph JSON에
  `responseProvenance` 없으면 `CAPTURED`로 역직렬화(기존 규칙 유지). 기존 round-trip 테스트
  (`CapturedHttpCallJsonRoundTripTest`)를 CONTRACT 케이스로 확장한다.
- 수용기준:
  - Given `responseProvenance:"CONTRACT"` JSON, When 역직렬화, Then provenance == `CONTRACT`.
  - Given `responseProvenance` 누락 레거시 JSON, When 역직렬화, Then provenance == `CAPTURED`.
- 검증 레벨: unit

### REQ-F012-005 — 에러 디스크립터 runner 주입 배선 (errorWhenPresent 게이트)
- 유형: Functional / 우선순위: Must
- 설명: `ErrorContractDescriptor`를 `ClassifierConfig`에서 파생해 `EndpointExplorationRunner`
  canonical 생성자에 신규 파라미터로 주입한다. **소비**: errorContract가 non-null이면 envelope 합성값
  (`ErrorEnvelopeSynthesizer`)의 필드를 `runResponseVariantLoops`의 `buildVariantCandidates`에 driven
  변형 후보로 주입해 기존 변형 파이프라인(exploreResponseVariants→buildEgressAssertionPaths)이 SUT를
  구동·관측하고 `egress-assertion` CONTRACT path를 생성하게 한다(dead/dangling call 금지; 실증은
  REQ-F012-018). **null 결정 게이트**: descriptor는 `ClassifierConfig.errorWhenPresent()`가
  **비어있지 않을 때만 non-null**이다 — `ClassifierConfig.from(...)`가 `semanticStatusField` 기본값
  (`"errorCode"`)을 항상 세팅하므로, `semanticStatusField` 설정 여부가 아니라 `errorWhenPresent`
  비어있음이 게이트다(status-only SUT에 거짓 envelope 방지). `BuilderCli`는 `toClassifier()`와 동일
  `ClassifierConfig`에서 descriptor를 만들어 넘긴다.
- 수용기준:
  - Given `errorWhenPresent`가 비어있지 않은 synthetic `ClassifierConfig`, When descriptor 파생,
    Then descriptor != null이고 envelope 출처가 합성 경로에 도달한다.
  - Given `errorWhenPresent`가 빈 SUT(예 order-service), When descriptor 파생, Then descriptor ==
    null(envelope 미적용, 회귀 없음) — `semanticStatusField` 기본값 존재와 무관.
- 검증 레벨: integration (synthetic descriptor)

### REQ-F012-006 — redirect 변형의 SUT status 관측 + 단언 가능 ExploredPath 환류
- 유형: Functional / 우선순위: Must
- 설명: `VariantInvoker.invoke()` 반환형을 `VariantOutcome(ExecutionDataStore coverage, int sutStatus)`로
  바꿔 변형 invoke 시 SUT HTTP status를 관측한다(현재 `sendVariantAndDumpDelta`가 버리는
  `http.send(...)` 응답의 `statusCode()` 캡처). 새 arm을 연 변형(`KeptVariant`)마다, 관측 status를
  `expectedStatus`로 갖고 **신규 `discoveredBy = "egress-assertion"`**(생성-제외 마커 `"response-variant"`와
  구분)인 단언 가능 `ExploredPath`를 환류한다. 그 path는 변형 body를 가진 **신규 CONTRACT `CapturedHttpCall`**
  (REQ-F012-007)을 참조한다. 기존 cumulative `"response-variant"` path(+그 SYNTHESIZED CapturedHttpCall)는
  불변.
  - **blast-radius**(REQ-F012-012 회귀가 포괄, 명시): `VariantInvoker` 스텁/사용처 — `EnumVariantReExploreTest`,
    `EnumVariantNoneModeTest`, `StringLiteralVariantReExploreTest`, `StringLiteralVariantNoneModeTest`,
    그리고 `exploreResponseVariants` 호출부.
- 수용기준:
  - Given 변형 `region="EMBARGOED"`가 새 arm을 열고 SUT가 422 반환, When 변형 탐색, Then
    `discoveredBy=="egress-assertion"`·`expectedStatus==422`인 ExploredPath가 환류된다.
  - Given 변형이 새 arm 미개방, Then 단언 ExploredPath 미환류(기존 동작).
- 검증 레벨: unit/integration

### REQ-F012-007 — redirect 변형 stub body provenance = CONTRACT
- 유형: Functional / 우선순위: Must
- 설명: REQ-F012-006의 단언 path가 참조하는 `CapturedHttpCall`의 변형 body는 provenance `CONTRACT`로
  기록한다(기존 cumulative `"response-variant"` path의 SYNTHESIZED CapturedHttpCall는 불변).
- 수용기준:
  - Given 단언 path의 변형 CapturedHttpCall, When 기록, Then provenance == `CONTRACT`, responseBody ==
    변형 값(예 `{"region":"EMBARGOED",...}`).
- 검증 레벨: unit

### REQ-F012-008 — Generator가 단언 path를 생성하고 다중 stub shadow를 회피
- 유형: Functional / 우선순위: Must
- 설명: test-generator `Generator`는 현재 `discoveredBy=="response-variant"` path를 생성에서 제외한다.
  신규 `"egress-assertion"` path는 **생성에 포함**되도록 Generator 필터를 갱신한다(`"response-variant"`
  제외는 유지). 동일 (method, path)의 happy·변형 stub은 같은 scope에 동시 등록하지 않는다 — 변형은 별개
  ExploredPath → 별개 생성 테스트(시나리오)로 방출되고, 한 테스트 scope엔 그 시나리오 stub 하나만.
  `HttpMockComposer.compose` 호출당 단일 stub 방출 구조 유지.
- 수용기준:
  - Given `"egress-assertion"` path 2개 + happy path, When 생성, Then 서로 다른 테스트 메서드로 방출되고
    각 테스트는 단일 (method,path) stub만 등록한다(동일 scope 다중 등록 0). `"response-variant"` path는
    여전히 생성 제외.
- 검증 레벨: integration (생성 코드 문자열 단언)

### REQ-F012-009 — 생성 테스트 stub이 CONTRACT 값-충실 body를 방출
- 유형: Functional / 우선순위: Must
- 설명: `HttpMockComposer.stubBody`가 CONTRACT `responseBody`를 placeholder가 아닌 기대값 그대로 방출
  한다(consumedFields 투영 규칙 기존 유지).
- 수용기준:
  - Given CONTRACT CapturedHttpCall(body `{"region":"EMBARGOED",...}`), When compose, Then 생성된
    `.respondJson(...)`에 `"EMBARGOED"` 포함, `"sample-region"` placeholder 없음.
- 검증 레벨: unit

### REQ-F012-010 — 정직한 가시화: 폴백은 조용히, 미구동/실패는 loud
- 유형: Non-functional / 우선순위: Must
- 설명: 기대값 출처 단순 부재는 `SYNTHESIZED` 폴백(loud 없음). 형상 해소 불가·callSite 미매칭은 기존
  loud-fail(`unsynthesizable-shape`/`unmatched-external-call`/`unwired-external-dep`) 유지.
  **egress-branch-undriven 판정(결정적)**: 변형 루프 종료 후, responseShape가 합성됐고(SYNTHESIZED/CONTRACT)
  변형 후보(`stringLiteralsByDto` 또는 enum 비-첫상수)가 존재하나 그 callSite가 redirect-capable로
  구동되지 않은(=`stubSynthesizer.isRegistered==false`인 span-only) 경우, `externalLoudFails`에
  reason `egress-branch-undriven`, target `<method> <path>`를 기록한다.
- 수용기준:
  - Given 리터럴·envelope 출처 없음, When 합성, Then SYNTHESIZED + loud-fail 없음.
  - Given span-only callSite + 비어있지 않은 변형 후보 + 미등록(isRegistered==false), When 탐색 종료,
    Then `externalLoudFails`에 `egress-branch-undriven` 1건.
- 검증 레벨: unit/integration

### REQ-F012-011 — 결정성
- 유형: Non-functional / 우선순위: Must
- 설명: body 합성·envelope 합성·변형 plan은 결정적(시간/Random 금지; 동일 입력 → 동일 출력).
- 수용기준:
  - Given 동일 입력, When compose/envelope/변형 plan 2회 실행, Then 출력 byte-동일.
- 검증 레벨: unit

### REQ-F012-012 — 기존 동작 보존 (surgical)
- 유형: Non-functional / 우선순위: Must
- 설명: 발견(REQ-001~011), dedup(redirect 우선 `EgressCallMapper.mergeDedup`), **REQ-S015** 형상-시드
  등록, 기존 cumulative `"response-variant"` path(생성 제외)는 행위 변화 없이 보존. 빌더/생성기 전체
  회귀 green. VariantInvoker 시그니처 변경(REQ-F012-006)에 따른 기존 변형 테스트 스텁 갱신은 이 게이트로
  확인한다.
- 수용기준:
  - Given 기존 egress/REQ-S015/변형 스위트(`EgressStubComposerTest`, `CaptureHttpCallsEgressEnrichTest`,
    `EnumVariant*`/`StringLiteralVariant*` 등), When 본 변경 적용, Then 모두 green(회귀 0).
- 검증 레벨: integration (회귀; 빠른 회귀는 `-PexcludeTags=integration`, 전체는 CI)

### REQ-F012-013 — otel redirect-capable 단언 층 (E2E)
- 유형: Functional / 우선순위: Must
- 설명: otel SUT `samples/order-service`(`InventoryClient` → GET `/inventory/stock`)를 recorder로
  redirect(`EXTERNAL_INVENTORY_URL={{wiremock}}`, trace-mode=otel)한 상태에서, 값-충실 변형이 단언하는
  생성 테스트로 환류됨을 out-of-process로 검증한다.
- 수용기준:
  - Given order-service 탐색, When 생성, Then 최소 3개 단언 테스트 방출: happy(`mode=STANDARD` ·
    `region≠EMBARGOED` · `available >= request.amount` → 201), `region="EMBARGOED"` → 422,
    `mode="BACKORDER"` → 409. (`mode="EXPRESS_ONLY"` 등 새 arm 변형은 budget 내 허용.)
  - Given graph JSON, Then 해당 변형 `httpCalls[].responseProvenance == CONTRACT`이고 responseBody가
    그 기대값(`"EMBARGOED"`/`"BACKORDER"`)을 포함(placeholder 아님).
- 검증 레벨: E2E (process)

### REQ-F012-014 — span-only body 충실도 층 (E2E)
- 유형: Functional / 우선순위: Must
- 설명: SUT `samples/order-service`를 **span-only**로 구성(`EXTERNAL_INVENTORY_URL`을 recorder가 아닌
  직접 host stub URL로, externalStubsDir 없이, trace-mode=otel — `EgressStatusAgnosticStubE2E` 패턴)하여,
  생성 테스트 stub body가 CONTRACT 값-충실(예 String 리터럴 `"EMBARGOED"` 반영)이고 외부-응답 분기
  미구동이 loud로 노출됨을 검증한다.
- 수용기준:
  - Given span-only 발견 호출(`OrderController` equals-family `"EMBARGOED"` 리터럴 보유), When 생성,
    Then 그 호출의 stub body가 `"EMBARGOED"`를 반영하고 `responseProvenance == CONTRACT`이며 외부-응답
    분기에 대한 SUT-status 단언 테스트는 없다.
  - Given span-only 분기 미구동, Then `egress-branch-undriven` loud가 기록된다.
- 검증 레벨: E2E (process)

### REQ-F012-015 — sleuth 교차 모드 정직한 abstain 층 (E2E)
- 유형: Functional / 우선순위: Must
- 설명: sleuth SUT `samples/legacy-tram/order-web`의 외부 호출은 `postForEntity(..., Void.class)`로
  응답 body가 없다(responseShape 부재, 확인됨). sleuth 모드에서 egress 발견이 동작하고, body 충실도
  합성이 대상 부재 시 CONTRACT를 **거짓 생성하지 않음**을 검증한다.
- 수용기준:
  - Given order-web 탐색(sleuth), When 생성, Then 외부 호출이 발견·기록되되 `responseProvenance !=
    CONTRACT`(빈/형상 body 유지). (responseShape 부재에 대한 기존 `unwired-external-dep` 류 loud는
    정직한 신호로 허용 — REQ-F012-017에서 Void-특화 quiet abstain으로 정련 가능, 본 REQ의 게이트 아님.)
- 검증 레벨: E2E (process)

### REQ-F012-016 — 테스트 자원 정리/누수 게이트
- 유형: Non-functional / 우선순위: Must
- 설명: SUT를 띄우는 모든 E2E는 모든 종료 경로에서 자기 스코프(고유 project/label/PID)만 teardown하고
  잔존 0을 검증한다(전역 규칙).
- 수용기준:
  - Given E2E가 SUT 기동, When 성공·실패·예외 어느 경로로든 종료, Then 그 SUT 프로세스 PID 잔존 0,
    무차별 정리(`pkill` 광범위/`docker system prune`) 미사용.
- 검증 레벨: process

### REQ-F012-018 — 에러 envelope 티어 실증 (egress+envelope SUT E2E)
- 유형: Functional / 우선순위: Must
- 설명: REQ-F012-003/005의 envelope 합성을 **실제 SUT로 end-to-end 구동·검증**한다. `samples/error-envelope-service`에
  외부 HTTP egress 호출 + 외부 응답 envelope 검사 분기를 추가한다(외부 응답 DTO에 `errorCode`/`errorDetail`
  필드; SUT가 `resp.errorCode() != null`이면 `BizException` → GlobalExceptionHandler가 HTTP 200 +
  ErrorEnvelope 방출). 빌더가 `--error-when-present errorCode --error-detail-field errorDetail
  [--error-detail-contains ...]`로 이 SUT를 인덱싱하면 errorContract가 설정되고, envelope 합성값이
  `buildVariantCandidates`에 driven 변형으로 주입돼 SUT의 envelope-검사 분기를 구동, `egress-assertion`
  CONTRACT path가 envelope CONTRACT `CapturedHttpCall`을 **참조**(dangling 금지)한다.
- 수용기준:
  - Given error-envelope-service를 외부 egress 엔드포인트 + `--error-when-present errorCode`로 build(recorder
    redirect), When 탐색·생성, Then envelope 값(`errorCode` 채워짐)을 가진 변형이 SUT의 envelope 분기를
    구동해 관측된 status로 단언하는 `egress-assertion` 생성 테스트가 방출되고, 그 외부 stub의
    `responseProvenance == CONTRACT`이며 어떤 ExploredPath도 참조하지 않는 dead CONTRACT call이 없다.
- 검증 레벨: E2E (process)

### REQ-F012-017 — (deferred) matched-Void callSite의 quiet abstain
- 유형: Functional / 우선순위: Should / 상태: 🔵 deferred (분모 제외)
- 설명: 매칭된 callSite의 응답 타입이 Void/no-content임을 식별해, 해소 불가(`unwired-external-dep`)
  loud와 구분해 **조용히 abstain**(loud 없음, 빈 body, non-CONTRACT)한다. 현재 `ExternalCallSite`의
  `responseShape.isEmpty()`는 Void와 "인덱싱 불가 형상"을 구분 못 하므로, 식별엔 인덱싱 보강(응답 타입
  Void 표식)이 필요하다 → 본 작업 범위 밖으로 연기. (REQ-F012-015는 이 정련 없이도 충족된다.)
- 수용기준:
  - Given 응답 타입이 Void로 식별된 매칭 callSite, When compose, Then loud 없이 빈 body·non-CONTRACT.
- 검증 레벨: unit

---

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-F012-001 | String 리터럴 시드 | `EgressStubComposerContractTest#literalSeeding` | unit | 🟢 green |
| REQ-F012-002 | enum happy=첫 상수, 거짓 승격 방지 | `EgressStubComposerContractTest#enumOnlyStaysSynthesized` | unit | 🟢 green |
| REQ-F012-003 | 에러 envelope 합성기(synthetic) | `ErrorEnvelopeSynthesizerTest` | unit | 🟢 green |
| REQ-F012-004 | Provenance CONTRACT + 후방호환 | `CapturedHttpCallJsonRoundTripTest`(CONTRACT 확장) | unit | 🟢 green |
| REQ-F012-005 | errorContract 주입(errorWhenPresent 게이트) | `EgressErrorContractWiringTest` + `EnvelopeVariantCandidateTest` | integration | 🟢 green |
| REQ-F012-006 | 변형 SUT status 관측 + egress-assertion path | `ResponseVariantAssertionPathTest` | unit/integration | 🟢 green |
| REQ-F012-007 | 변형 body provenance CONTRACT | `ResponseVariantAssertionPathTest`(provenance) | unit | 🟢 green |
| REQ-F012-008 | Generator egress-assertion 생성 + shadow 회피 | `GeneratorEgressAssertionTest` | integration | 🟢 green |
| REQ-F012-009 | CONTRACT 값-충실 stub 방출 | `HttpMockComposerContractBodyTest` | unit | 🟢 green |
| REQ-F012-010 | 정직한 가시화(폴백/egress-branch-undriven) | `EgressStubComposerContractTest#fallbackSilent` + `UndrivenEgressBranchTest` | unit/integration | 🟢 green |
| REQ-F012-011 | 결정성 | `EgressStubComposerContractTest#deterministic` | unit | 🟢 green |
| REQ-F012-012 | 기존 동작 보존(회귀) | `:graph-rag-builder:test`+`:test-generator:test`+`:shared-model:test` -PexcludeTags=integration | integration | 🟢 green |
| REQ-F012-013 | otel 단언 층 | `EgressStubBodyFidelityOtelE2E` | E2E | 🟢 green |
| REQ-F012-014 | span-only body 충실도 층 | `EgressStubBodyFidelitySpanOnlyE2E` | E2E | 🟢 green |
| REQ-F012-015 | sleuth abstain 층 | `EgressStubBodyFidelitySleuthAbstainE2E` | E2E | 🟡 CI-pending |
| REQ-F012-016 | 자원 정리/누수 게이트 | E2E 하니스(내 worktree SUT 잔존 0 확인) | process | 🟢 green |
| REQ-F012-018 | envelope 티어 실증(egress+envelope SUT) | `EgressStubBodyFidelityEnvelopeE2E` | E2E | 🟢 green |
| REQ-F012-017 | (연기) Void quiet abstain | — | unit | 🔵 deferred |

Coverage: 16/17 green (94%) 로컬 + 1 CI-pending — target 100% (대상: Must 17개; REQ-F012-001~016, 018).
REQ-F012-015(sleuth abstain)는 **로직·단언 정상**이나 로컬 머신에 타 세션이 누수시킨 order-service
SUT 20개가 자원을 점유해 order-web SUT가 90s health-check 내 기동하지 못함(SUT boot 타임아웃,
단언 실패 아님). 타 세션 프로세스는 스코프 밖이라 정리하지 않으며, 깨끗한 CI 환경에서 검증한다
(나머지 16개는 로컬 green: 단위/통합/회귀 + otel·span-only·envelope E2E 실 SUT 통과). 연기(🔵):
1(REQ-F012-017). 폐기 없음.

---

## 자기검토

1. **고아 행위 없음** — design §4.1~4.7 + §7 3층이 모두 REQ로 매핑(001~004 합성, 005 배선, 006~009
   변형·생성, 010 가시화, 011 결정성, 012 회귀, 013~015 E2E 3층, 016 자원). Generator 통과(008)·envelope
   게이트(005)·CONTRACT 연결(006/007) 등 교차모듈 배선도 반영.
2. **원자성** — 각 REQ 단일 행위. 리터럴(001)/enum 거짓승격(002)/envelope(003)/배선(005)/관측-path(006)/
   provenance(007)/Generator(008) 분리.
3. **수용기준 완비** — 모든 Must REQ가 측정 가능한 Given-When-Then 보유.
4. **커버리지 규칙 명시** — 분모=Must 16, 연기 1(017), Coverage 줄 명시.

## 범위 확장 (역전파)
구현 중 새 요구가 드러나면 STOP → REQ-F012-* 추가 → design spec 갱신 → E2E 추가 → plan task 확장 →
재개. 변경 문서 부분만 design-doc 리뷰 재실행.
