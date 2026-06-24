# 외부 stub 응답 body 충실도 (REQ-012) 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-24-egress-stub-body-fidelity-design.md
> 부모 추적: `docs/superpowers/requirements/2026-06-24-egress-span-capture-requirements.md`의
> REQ-012(🔵 Won't)를 본 명세에서 `REQ-F012-*`로 구체화한다(ID 충돌 방지·안정 추적).
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
  `stringLiteralsByDto`의 소비 코드 equals-family 리터럴이 있으면 그 값(첫 리터럴, 결정적)을 채워
  happy body를 합성한다.
- 수용기준:
  - Given responseShape에 String 필드 `region`과 `stringLiteralsByDto[dto][region]={"EMBARGOED"}`,
    When compose 호출, Then body의 `region == "EMBARGOED"`이고 provenance == `CONTRACT`.
  - Given String 필드에 추출 리터럴이 없음, When compose, Then 그 필드는 `ShapeJsonSynthesizer`
    기본값(`"sample-region"`)을 유지한다.
- 검증 레벨: unit

### REQ-F012-002 — enum happy 값은 첫 상수(SYNTHESIZED 동치) 유지, CONTRACT 승격은 리터럴/envelope 출처에 한정
- 유형: Functional / 우선순위: Must
- 설명: enum 응답 필드 happy 값은 `ShapeJsonSynthesizer` 규칙(선언순 첫 상수)을 그대로 쓴다. body가
  CONTRACT로 승격되는 것은 String 리터럴 또는 에러 envelope 출처가 하나라도 적용된 경우뿐이며,
  enum-only(리터럴/envelope 없음)면 형상-시드와 동일하므로 `SYNTHESIZED`로 남는다.
- 수용기준:
  - Given enum 필드만 있고 String 리터럴·envelope 출처 없음, When compose, Then enum 값은 첫 상수,
    provenance == `SYNTHESIZED`(거짓 CONTRACT 승격 없음).
- 검증 레벨: unit

### REQ-F012-003 — 에러 envelope 합성기
- 유형: Functional / 우선순위: Must
- 설명: `ErrorContractDescriptor`(`semanticStatusField`/`errorDetailField`/`errorDetailContains`)가
  non-null이면 에러 envelope JSON을 합성한다(있는 필드만 채움). 외부 stub status는 200 + envelope
  body를 기본으로 한다. descriptor가 null이면 envelope 변형을 생략한다(loud 없음 — 정상 부재).
- 수용기준:
  - Given descriptor `{semanticStatusField:"errorCode", errorDetailField:"errorDetail",
    errorDetailContains:"BizException"}`, When envelope 합성, Then JSON에 `errorCode`·`errorDetail`
    키가 있고 `errorDetail` 값이 `"BizException"`을 포함한다.
  - Given descriptor == null, When 합성 시도, Then envelope 변형은 생성되지 않고 loud-fail도 없다.
- 검증 레벨: unit

### REQ-F012-004 — `CapturedHttpCall.Provenance`에 CONTRACT 추가 + 후방 호환
- 유형: Functional / 우선순위: Must
- 설명: provenance enum에 `CONTRACT`를 추가한다(`{CAPTURED, SYNTHESIZED, CONTRACT}`). 레거시 graph
  JSON에 `responseProvenance` 필드가 없으면 `CAPTURED`로 역직렬화한다(기존 규칙 유지).
- 수용기준:
  - Given `responseProvenance:"CONTRACT"` JSON, When 역직렬화, Then provenance == `CONTRACT`.
  - Given `responseProvenance` 누락 레거시 JSON, When 역직렬화, Then provenance == `CAPTURED`.
- 검증 레벨: unit

### REQ-F012-005 — 에러 디스크립터 runner 주입 배선
- 유형: Functional / 우선순위: Must
- 설명: `ErrorContractDescriptor`를 `ClassifierConfig`에서 파생해 `EndpointExplorationRunner`
  canonical 생성자에 신규 파라미터로 주입하고, `captureHttpCalls`가 이를 `EgressStubComposer.compose`로
  전달한다. `BuilderCli`는 `toClassifier()`와 동일 `ClassifierConfig`에서 descriptor를 만들어 넘긴다.
  descriptor 미설정 SUT는 null(envelope 미적용).
- 수용기준:
  - Given errorContract 디스크립터가 인덱싱된 SUT, When 탐색, Then egress stub 합성 시 envelope 출처가
    compose에 도달한다(통합 검증: envelope 변형이 생성됨).
  - Given errorContract 미설정 SUT, When 탐색, Then null descriptor로 envelope 없이 진행(회귀 없음).
- 검증 레벨: integration

### REQ-F012-006 — redirect 변형의 SUT status 관측 + 단언 가능 ExploredPath 환류
- 유형: Functional / 우선순위: Must
- 설명: `VariantInvoker.invoke()` 반환형을 `VariantOutcome(ExecutionDataStore coverage, int sutStatus)`로
  바꿔 변형 invoke 시 SUT HTTP status를 관측한다. 새 arm을 연 변형(`KeptVariant`)마다, 관측 status를
  `expectedStatus`로 갖는(생성-제외 마커 없는) 단언 가능 `ExploredPath`를 환류한다(기존 cumulative
  변형 path는 불변).
- 수용기준:
  - Given 변형 stub `region="EMBARGOED"`가 새 arm을 열고 SUT가 422 반환, When 변형 탐색, Then
    `expectedStatus == 422`이고 생성-제외 마커가 없는 ExploredPath가 환류된다.
  - Given 변형이 새 arm을 못 엶, Then 단언 ExploredPath는 환류되지 않는다(기존 동작).
- 검증 레벨: unit/integration

### REQ-F012-007 — redirect 변형 stub body provenance = CONTRACT
- 유형: Functional / 우선순위: Must
- 설명: redirect per-variant 단언 경로가 환류하는 `CapturedHttpCall`의 변형 body는 provenance
  `CONTRACT`로 기록한다(기존 cumulative 변형 path의 SYNTHESIZED는 불변).
- 수용기준:
  - Given 단언 ExploredPath의 변형 CapturedHttpCall, When 기록, Then provenance == `CONTRACT`,
    responseBody == 변형 값(예 `region="EMBARGOED"`).
- 검증 레벨: unit

### REQ-F012-008 — 변형 다중 stub shadow 회피 (별개 테스트 시나리오)
- 유형: Functional / 우선순위: Must
- 설명: 동일 (method, path)의 happy·변형 stub은 같은 scope에 동시 등록하지 않는다. 변형은 별개
  `ExploredPath` → 별개 생성 테스트로 방출하고, 한 테스트 scope에는 그 시나리오 stub 하나만 등록한다.
  `HttpMockComposer.compose`의 호출당 단일 stub 방출 구조를 유지한다.
- 수용기준:
  - Given 한 endpoint에 happy + 2개 변형 path, When 생성, Then 서로 다른 테스트 메서드/시나리오로
    방출되고 각 테스트는 단일 (method,path) stub만 등록한다(동일 scope 다중 등록 0).
- 검증 레벨: integration (생성 코드 문자열 단언)

### REQ-F012-009 — 생성 테스트 stub이 CONTRACT 값-충실 body를 방출
- 유형: Functional / 우선순위: Must
- 설명: `HttpMockComposer.stubBody`가 CONTRACT `CapturedHttpCall.responseBody`를 placeholder가 아닌
  그 기대값 그대로 방출한다(consumedFields 투영 규칙은 기존 유지).
- 수용기준:
  - Given CONTRACT CapturedHttpCall(body `{"region":"EMBARGOED",...}`), When compose, Then 생성된
    `.respondJson(...)`에 `"EMBARGOED"`가 포함되고 `"sample-region"` placeholder는 없다.
- 검증 레벨: unit

### REQ-F012-010 — 정직한 가시화: 폴백은 조용히, 미구동/실패는 loud
- 유형: Non-functional / 우선순위: Must
- 설명: 기대값 출처 단순 부재는 `SYNTHESIZED` 폴백(loud 없음). span-only에서 외부-응답 분기가 존재하나
  구동 불가하면 loud(`egress-branch-undriven`). 형상 해소 불가·callSite 미매칭은 기존 loud-fail
  (`unsynthesizable-shape`/`unmatched-external-call`/`unwired-external-dep`) 유지.
- 수용기준:
  - Given 리터럴·envelope 출처 없음, When 합성, Then SYNTHESIZED + loud-fail 없음.
  - Given span-only 분기 미구동, When 탐색 종료, Then `externalLoudFails`에 `egress-branch-undriven`
    reason이 기록된다.
- 검증 레벨: unit/integration

### REQ-F012-011 — 결정성
- 유형: Non-functional / 우선순위: Must
- 설명: body 합성·변형 plan·envelope 합성은 결정적이다(시간/Random 금지; 동일 입력 → 동일 출력).
- 수용기준:
  - Given 동일 입력, When compose/envelope/변형 plan 2회 실행, Then 출력 byte-동일.
- 검증 레벨: unit

### REQ-F012-012 — 기존 동작 보존 (surgical)
- 유형: Non-functional / 우선순위: Must
- 설명: 발견(REQ-001~011), dedup(redirect 우선 `EgressCallMapper.mergeDedup`), REQ-015 형상-시드 등록,
  기존 cumulative 변형 path(생성 제외)는 행위 변화 없이 보존한다. 빌더 전체 회귀가 green.
- 수용기준:
  - Given 기존 egress/REQ-015 테스트 스위트, When 본 변경 적용, Then 모두 green(회귀 0).
- 검증 레벨: integration (회귀)

### REQ-F012-013 — otel redirect-capable 단언 층 (E2E)
- 유형: Functional / 우선순위: Must
- 설명: otel SUT `samples/order-service`(`InventoryClient` → GET `/inventory/stock`)를 recorder로
  redirect 한 상태에서, 값-충실 변형이 단언하는 생성 테스트로 환류됨을 out-of-process로 검증한다.
- 수용기준:
  - Given order-service 탐색, When 생성, Then 최소 3개 단언 테스트가 방출된다: happy(예 STANDARD,
    region≠EMBARGOED → 201), `region="EMBARGOED"` → 422, `mode="BACKORDER"` → 409. (EXPRESS_ONLY 등
    추가 arm 변형은 budget 내 허용.)
  - Given graph JSON, Then 해당 변형 `httpCalls[].responseProvenance == CONTRACT`이고 responseBody가
    그 기대값(`"EMBARGOED"`/`"BACKORDER"`)을 포함한다(placeholder 아님).
- 검증 레벨: E2E (process)

### REQ-F012-014 — span-only body 충실도 층 (E2E)
- 유형: Functional / 우선순위: Must
- 설명: 외부 호출이 recorder를 거치지 않는 발견 경로에서, 생성 테스트 stub body가 CONTRACT 값-충실
  이고 미구동 분기가 loud로 노출됨을 검증한다.
- 수용기준:
  - Given span-only 발견 호출(String 리터럴 보유), When 생성, Then stub body가 그 리터럴을 반영하고
    `responseProvenance == CONTRACT`이며 외부-응답 분기에 대한 SUT-status 단언은 없다.
  - Given span-only 분기 미구동, Then loud(`egress-branch-undriven`)가 기록된다.
- 검증 레벨: E2E (process)

### REQ-F012-015 — sleuth 교차 모드 정직한 abstain 층 (E2E)
- 유형: Functional / 우선순위: Must
- 설명: sleuth SUT `samples/legacy-tram/order-web`의 외부 호출은 `postForEntity(..., Void.class)`로
  응답 body가 없다(responseShape 부재). sleuth 모드에서 egress 발견이 동작하고, body 충실도 합성이
  대상 부재 시 CONTRACT를 거짓 생성하지 않고 정직히 abstain함을 검증한다.
- 수용기준:
  - Given order-web 탐색(sleuth), When 생성, Then 외부 호출이 발견·기록되되 거짓 CONTRACT body가
    부여되지 않는다(provenance != CONTRACT; 빈/형상 body 유지).
- 검증 레벨: E2E (process)

### REQ-F012-016 — 테스트 자원 정리/누수 게이트
- 유형: Non-functional / 우선순위: Must
- 설명: SUT/컨테이너를 띄우는 모든 E2E는 모든 종료 경로에서 자기 스코프(고유 project/label/PID)만
  teardown하고 잔존 0을 검증한다(전역 규칙).
- 수용기준:
  - Given E2E가 SUT 기동, When 성공·실패·예외 어느 경로로든 종료, Then 그 SUT 프로세스 PID 잔존 0,
    무차별 정리(`pkill` 광범위/`docker system prune`) 미사용.
- 검증 레벨: process

---

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-F012-001 | String 리터럴 시드 | `EgressStubComposerContractTest#literalSeeding` | unit | 🔴 planned |
| REQ-F012-002 | enum happy=첫 상수, 거짓 승격 방지 | `EgressStubComposerContractTest#enumOnlyStaysSynthesized` | unit | 🔴 planned |
| REQ-F012-003 | 에러 envelope 합성기 | `ErrorEnvelopeSynthesizerTest` | unit | 🔴 planned |
| REQ-F012-004 | Provenance CONTRACT + 후방호환 | `CapturedHttpCallProvenanceTest` | unit | 🔴 planned |
| REQ-F012-005 | errorContract runner 주입 배선 | `EgressErrorContractWiringTest` | integration | 🔴 planned |
| REQ-F012-006 | 변형 SUT status 관측 + 단언 path | `ResponseVariantAssertionPathTest` | unit/integration | 🔴 planned |
| REQ-F012-007 | 변형 body provenance CONTRACT | `ResponseVariantAssertionPathTest#provenanceContract` | unit | 🔴 planned |
| REQ-F012-008 | 다중 stub shadow 회피 | `HttpMockComposerVariantScenarioTest` | integration | 🔴 planned |
| REQ-F012-009 | CONTRACT 값-충실 stub 방출 | `HttpMockComposerContractBodyTest` | unit | 🔴 planned |
| REQ-F012-010 | 정직한 가시화(폴백/loud) | `EgressStubComposerContractTest#fallbackSilent` + `ResponseVariantAssertionPathTest#undrivenLoud` | unit/integration | 🔴 planned |
| REQ-F012-011 | 결정성 | `EgressStubComposerContractTest#deterministic` | unit | 🔴 planned |
| REQ-F012-012 | 기존 동작 보존(회귀) | `:graph-rag-builder:test` (egress/REQ-015 스위트) | integration | 🔴 planned |
| REQ-F012-013 | otel 단언 층 | `EgressStubBodyFidelityOtelE2E` | E2E | 🔴 planned |
| REQ-F012-014 | span-only body 충실도 층 | `EgressStubBodyFidelitySpanOnlyE2E` | E2E | 🔴 planned |
| REQ-F012-015 | sleuth abstain 층 | `EgressStubBodyFidelitySleuthAbstainE2E` | E2E | 🔴 planned |
| REQ-F012-016 | 자원 정리/누수 게이트 | E2E 하니스(고유 project/PID teardown 검증) | process | 🔴 planned |

Coverage: 0/16 green (0%) — target 100% (대상: Must 16개 전부). 연기(🔵)·폐기 없음.

---

## 자기검토

1. **고아 행위 없음** — design §4.1~4.7 변경과 §7 E2E 3층이 모두 REQ로 매핑됨(001~012 단위/통합,
   013~015 E2E 3층, 016 자원). §2 충실도 사다리는 001/002/003/004로 분해됨.
2. **원자성** — 각 REQ는 단일 행위. "리터럴 시드"(001)와 "enum 거짓승격 방지"(002), "envelope
   합성"(003)과 "디스크립터 배선"(005)을 분리함.
3. **수용기준 완비** — 모든 REQ가 Given-When-Then 보유, 측정 가능.
4. **커버리지 규칙 명시** — 분모=Must 16, 제외 없음, Coverage 줄 명시.

## 범위 확장 (역전파)

구현 중 새 요구가 드러나면 STOP → 본 명세에 REQ-F012-* 추가 → design spec 갱신 → E2E 추가 →
plan task 확장 → 재개. 변경된 문서 부분만 design-doc 리뷰 재실행.

## sleuth 샘플 단서 (REQ-F012-015 확정 노트)
plan/구현 단계에서 `samples/legacy-tram` 내 응답 DTO를 반환하는 외부 호출이 있으면 sleuth CONTRACT
body 직접 검증으로 격상; 없으면 본 명세대로 abstain 검증으로 한정한다.
