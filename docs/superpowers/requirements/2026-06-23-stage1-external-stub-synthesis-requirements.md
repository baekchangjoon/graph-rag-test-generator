# 단계1 형상-only 외부 응답 stub 합성 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-23-stage1-external-stub-synthesis-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

## 요구사항 목록

### REQ-001 — 외부 stub 없이 형상 합성 응답으로 외부-의존 경로 통과
- 유형: Functional
- 우선순위: Must
- 설명: `--external-stubs` 없이도 SUT의 외부 HTTP 호출이 형상-only 합성 응답(200)을 받아 외부 호출 직후 분기까지 탐색이 진입한다.
- 수용기준:
  - Given `samples/order-service`(`InventoryClient` → `GET /inventory/stock`, 응답 `InventoryResponse(Integer available)`)를 `--external-stubs` 없이 빌드, When 탐색이 외부 호출을 유발, Then 그 호출이 합성 stub으로 200을 수신하고 외부 호출 직후 분기가 커버리지에 도달한다.
- 검증 레벨: E2E black-box

### REQ-002 — 수동 stub 대비 동등 커버리지
- 유형: Functional
- 우선순위: Must
- 설명: 자동 합성 경로가 운영자 수동 stub과 동등한 외부-의존 경로 커버리지를 낸다.
- 수용기준:
  - Given 동일 SUT를 (a) 수동 stub + (b) 자동 합성으로 각각 빌드, When 두 결과의 외부-의존 분기 커버리지를 비교, Then (b)가 (a) 이상이다(외부 직후 분기 누락 없음).
- 검증 레벨: E2E black-box

### REQ-003 — 결정성 (동일 commit → 동일 stub)
- 유형: Non-functional
- 우선순위: Must
- 설명: 형상-only 합성은 외부 의존·캐시 없이 순수 결정적이다.
- 수용기준:
  - Given 동일 commit에서 동일 SUT를 2회 빌드, When 합성 stub body와 커버리지를 비교, Then 합성 stub은 byte-동일이고 커버리지가 동일하다.
- 검증 레벨: E2E black-box

### REQ-004 — 응답 DTO 형상 추출 (인덱싱)
- 유형: Functional
- 우선순위: Must
- 설명: 외부 클라이언트 호출 site에서 `(httpMethod, pathLiteral, 응답 DTO BodyShape)`를 추출한다. 배열 타입(`Dto[].class`)은 `[]` strip 후 component 타입으로 해석한다.
- 수용기준:
  - Given `getForObject(baseUrl+"/inventory/stock?...", InventoryResponse.class)`, When `extractCallSites`, Then `(GET, "/inventory/stock", BodyShape(available:Integer))`를 반환한다.
  - Given `getForObject(url, Dto[].class)`, When 추출, Then component `Dto`의 BodyShape(collection=true)를 반환한다.
  - Given 기존 `List<Set<String>>` 필드명 추출(consumedFields), When 신규 메서드 추가, Then 기존 추출은 회귀 없이 유지되고 Spoon 모델은 1회만 파싱된다(SharedSpoonModel).
- 검증 레벨: integration (Spoon 모델 기반 unit)

### REQ-005 — 미추출 응답 타입 skip
- 유형: Functional
- 우선순위: Must
- 설명: 응답 타입을 정적으로 못 뽑는 형태(`ParameterizedTypeReference` 제네릭, WebClient `bodyToMono`, Feign 반환, `exchange` 변수/필드 인자)는 `empty`로 표시하고 합성하지 않는다.
- 수용기준:
  - Given 응답 타입이 제네릭/변수 인자인 호출 site, When `extractCallSites`, Then `responseShape == empty`이고 합성 대상에서 제외된다.
- 검증 레벨: integration (Spoon 모델 기반 unit)

### REQ-006 — ShapeJsonSynthesizer 형상→minimal JSON (값 규칙 공유)
- 유형: Functional
- 우선순위: Must
- 설명: `BodyShape`에서 minimal valid JSON을 결정적으로 합성한다(Integer→1, String→`sample-<field>`, enum→선언순 첫 상수, Boolean→true, scalar/enum/collection 1-레벨; 중첩 객체 응답 DTO는 unsynthesizable-shape loud-fail). 값 규칙은 `SampleInputSynthesizer`와 공유 헬퍼로 추출하되 기존 입력 동작을 보존한다.
- 수용기준:
  - Given `BodyShape(available:Integer)`, When `synthesizeBody`, Then `{"available":1}`을 반환한다.
  - Given enum 필드, When 합성, Then 선언순 첫 상수가 들어가 SUT 역직렬화가 성공한다(입력 동작 보존).
  - Given Boolean 필드, When 합성, Then `true`를 반환한다(입력 동작 보존).
  - Given 공유 헬퍼 추출 리팩터, When `SampleInputSynthesizer` 기존 단위 테스트 실행, Then 전부 green을 유지한다.
- 검증 레벨: integration (unit)

### REQ-007 — 모드-중립 trace-id 귀속 (TraceKey)
- 유형: Functional
- 우선순위: Must
- 설명: outbound 외부 호출의 trace-id를 활성 `--trace-mode`별로 읽어 캡처를 요청에 귀속한다(otel: `traceparent`, sleuth: `X-B3-TraceId`, none: empty). `drainNewExchanges`는 현재 `baggage`만 읽으므로 헤더 추출을 신규로 추가한다.
- 수용기준:
  - Given otel `traceparent: 00-<tid>-<sid>-01` outbound 헤더, When `OtelTraceKey.readTraceId`, Then `<tid>`를 반환한다.
  - Given sleuth `X-B3-TraceId: <tid>`, When `SleuthTraceKey.readTraceId`, Then `<tid>`를 반환한다.
  - Given none 모드, When `NoTraceKey.readTraceId`, Then `empty`를 반환한다.
- 검증 레벨: integration (unit); otel 경로는 REQ-001 E2E에 동반

### REQ-008 — 재탐색 루프 수렴 (B2)
- 유형: Functional
- 우선순위: Must
- 설명: 1차 invoke의 unmatched(404) 외부 호출에 stub을 합성·등록하고 endpoint를 재invoke하며, 새 stub이 없을 때까지 상한 K회 반복한다. 등록은 멱등이고 재invoke 시 커버리지 baseline을 리셋한다.
- 수용기준:
  - Given unmatched 외부 호출, When 합성·등록 후 재invoke, Then 그 호출이 200을 받고 새 분기가 열린다.
  - Given 더 이상 새 stub이 없음, When 루프, Then K 도달 전 종료한다(수렴).
  - Given 동일 (method, pathLiteral), When 재차 매칭, Then 재등록하지 않는다(멱등).
- 검증 레벨: integration

### REQ-009 — 캡처 URL ↔ pathLiteral 매칭
- 유형: Functional
- 우선순위: Must
- 설명: 캡처 `urlPath`(query strip)와 인덱싱 `pathLiteral`을 segment 경계 `endsWith`로 비교하고, 복수 매치는 최장 path 우선(동률 시 첫 매치 + WARN), method도 일치해야 한다.
- 수용기준:
  - Given 캡처 `/inventory/stock`와 인덱싱 `pathLiteral=/inventory/stock`, When `matchCallSite(GET, ...)`, Then 매치한다.
  - Given 두 site가 같은 path suffix를 공유, When 매칭, Then 더 긴 pathLiteral이 선택된다.
- 검증 레벨: integration (unit)

### REQ-010 — loud-fail surface (silent 금지)
- 유형: Functional
- 우선순위: Must
- 설명: 외부 호출이 합성되지 못한 사유를 리포트에 기록한다 — `unwired-external-dep`(미추출 타입), `unmatched-external-call`(매칭 실패), `unsynthesizable-shape`(복잡 형상), `stub-ineffective`(등록 후 미도달).
- 수용기준:
  - Given 응답 타입 미추출 호출 site를 가진 SUT, When 빌드, Then 리포트에 `unwired-external-dep ... fallback=stage3`가 기록된다.
  - Given 중첩 객체 필드를 가진 응답 DTO site, When 합성, Then stub을 등록하지 않고 `unsynthesizable-shape`가 기록된다(silent String stub 금지).
  - Given stub 등록 후에도 재invoke에서 여전히 404인 호출, When 수렴 후 점검, Then `stub-ineffective`가 기록된다.
- 검증 레벨: integration

### REQ-011 — provenance 태깅
- 유형: Functional
- 우선순위: Must
- 설명: 합성 stub으로 캡처된 `CapturedHttpCall`은 `responseProvenance=SYNTHESIZED`, 실제 외부 응답은 `CAPTURED`로 구분된다.
- 수용기준:
  - Given 합성 stub으로 통과한 외부 호출, When 캡처, Then 그 `CapturedHttpCall.responseProvenance == SYNTHESIZED`.
  - Given 수동 stub(ground-truth) 응답, When 캡처, Then `CAPTURED`.
- 검증 레벨: E2E black-box

### REQ-012 — CapturedHttpCall 하위호환
- 유형: Functional
- 우선순위: Must
- 설명: `responseProvenance` 필드 추가가 기존 직렬화·생성자 호출부를 깨지 않는다.
- 수용기준:
  - Given `responseProvenance` 없는 레거시 graph.json, When 역직렬화, Then `CAPTURED`로 기본 처리된다.
  - Given 기존 생성자 시그니처 호출부, When 컴파일, Then compat 생성자로 깨지지 않는다.
- 검증 레벨: integration (unit, JsonRoundTrip)

### REQ-013 — attach 모드 회귀 없음
- 유형: Non-functional
- 우선순위: Should
- 설명: 단계1은 analysis 우선이며, attach 모드의 기존 `--external-stubs`/`--sut-env` 배선이 회귀하지 않는다.
- 수용기준:
  - Given attach 모드 기존 테스트, When 단계1 변경 적용, Then 기존 attach 캡처 테스트가 green을 유지한다.
- 검증 레벨: integration

### REQ-014 — none 모드 직렬 폴백
- 유형: Non-functional
- 우선순위: Should
- 설명: `--trace-mode none`에서 trace-id 없이 count-delta 귀속으로 합성·등록·재탐색이 직렬 전제로 동작한다.
- 수용기준:
  - Given none 모드, When 외부 호출 캡처, Then 1차 invoke 직후 drain 결과 전체를 해당 요청 unmatched로 귀속해 합성·통과한다.
- 검증 레벨: integration

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | 외부 stub 없이 형상 합성 통과 | `Stage1ExternalStubSynthesisE2E#synthesizedStubPassesExternalCall` | E2E | 🟢 green |
| REQ-002 | 수동 stub 대비 동등 커버리지 | `Stage1ExternalStubSynthesisE2E#equivalentCoverageToManualStub` | E2E | 🟢 green |
| REQ-003 | 결정성(동일 stub) | `Stage1ExternalStubSynthesisE2E#deterministicAcrossRuns` | E2E | 🟢 green |
| REQ-004 | 응답 DTO 형상 추출 | `ResponseDtoIndexerCallSiteTest` | integration | 🟢 green |
| REQ-005 | 미추출 타입 skip | `ResponseDtoIndexerCallSiteTest#unextractableResponseTypeYieldsEmptyShape` | integration | 🟢 green |
| REQ-006 | ShapeJsonSynthesizer | `ShapeJsonSynthesizerTest` + `SampleInputSynthesizer*Test`(green 유지) | unit | 🟢 green |
| REQ-007 | 모드-중립 trace-id | `TraceKeyTest` | unit | 🟢 green |
| REQ-008 | 재탐색 루프 수렴 | `ExternalStubReExploreTest` | integration | 🟢 green |
| REQ-009 | URL↔pathLiteral 매칭 | `CallSiteMatcherTest` | unit | 🟢 green |
| REQ-010 | loud-fail surface | `ExternalStubReExploreTest#unmatchedCallWithNoSiteRecordsLoudFail` (`unmatched-external-call`), `#matchedSiteWithEmptyShapeRecordsUnwiredLoudFail` (`unwired-external-dep`), `#nestedObjectResponseDtoRecordsUnsynthesizableShapeLoudFail` (`unsynthesizable-shape`); `ExternalStubLoudFailTest#stubRegisteredButStill404RecordsStubIneffective` (`stub-ineffective`) | integration | 🟢 green |
| REQ-011 | provenance 태깅 | `Stage1ExternalStubSynthesisE2E#synthesizedProvenanceTagged` | E2E | 🟢 green |
| REQ-012 | CapturedHttpCall 하위호환 | `CapturedHttpCallJsonRoundTripTest` | unit | 🟢 green |
| REQ-013 | attach 회귀 없음 | 기존 attach 통합 테스트 suite (`AttachCliConfigTest`, `AttachedComposeEnvironmentTest`, `OtelHttpCaptureIntegrationTest` 등) | integration | 🟢 green |
| REQ-014 | none 모드 직렬 폴백 | `ExternalStubNoneModeTest` | integration | 🟢 green |

Coverage: 14/14 green (100%) — target 100% (대상: Must 12 + Should 2, 모두 미연기). Won't/deferred 없음.

> 검증 결과(2026-06-23): Stage1 E2E 4 메서드(REQ-001~003,011) green — system-out에
> `re-explored post-api-orders after synthesizing 1 external stub(s) (round 1)`로 합성 경로 실증.
> `ExternalStubNoneModeTest`(REQ-014) green. REQ-004~010,012 단위/통합은 Task 2~11에서 green
> (`./gradlew test` 전체 회귀에 포함). REQ-013은 attach/HttpCapture 통합 suite green 유지로 확인.

## 단계 경계 (이 명세에서 제외 — 🔵 out-of-scope)

- **응답 값 변형 fuzzing**(enum 상수 조합·status 리터럴·숫자 경계로 값-의존 분기 전체 열기) → 단계2.
- **LLM / OpenAPI 응답 합성** → 단계3.
- **병렬 탐색 실행** → 후속(단계1은 메커니즘만 병렬-safe, 실행은 직렬).
- **생성 테스트 코드의 provenance 표식 문법** → test-generator 후속.
