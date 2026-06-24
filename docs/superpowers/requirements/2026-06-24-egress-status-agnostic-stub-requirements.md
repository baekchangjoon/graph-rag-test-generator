# REQ-015 status-무관 stub register 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-24-egress-status-agnostic-stub-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)
> 선행 요구사항: docs/superpowers/requirements/2026-06-24-egress-span-capture-requirements.md
>   (이 작업은 그 문서의 REQ-015를 deferred→in-scope로 활성화하고, REQ-005 수용기준을 정정한다.
>    본 명세의 REQ-ID는 그와 **별도 네임스페이스**(REQ-S015-NNN)로 충돌을 피한다.)

## 배경

1순위(egress-span-capture, PR #95)로 redirect 없이 OTEL/Brave CLIENT span에서 외부 호출이
발견되나, 그 호출의 생성 테스트 stub은 빈 body(`respondJson(status, "")`)로만 등록된다. 본 작업은
span-발견 호출을 정적 인덱스(`ExternalCallSite`)에 매칭해 **형상-시드 body**로 stub을 등록하는
status-무관(404 비의존) 경로를 추가한다. body 충실도(실측 body·에러 계약)는 별도(원 REQ-012).

## 요구사항 목록

### REQ-S015-001 — 매칭 성공 시 형상-시드 body로 SYNTHESIZED 기록
- 유형: Functional / 우선순위: Must
- 설명: span-발견 `EgressCall`이 callSite에 매칭되고 `responseShape`가 있으면, 형상에서 합성한
  body를 가진 `CapturedHttpCall`로 기록한다.
- 수용기준:
  - Given `callSites`가 비어있지 않고 `(method, path)`가 `responseShape` 보유 `ExternalCallSite`에
    매칭, When `captureHttpCalls`가 그 `EgressCall`을 처리, Then 결과 `CapturedHttpCall.responseBody`
    가 비어있지 않은 형상 JSON이고 `responseProvenance == SYNTHESIZED`이며 `responseStatus`는
    `statusOrNull`(없으면 200)이다.
- 검증 레벨: integration (graph-rag-builder, in-process)

### REQ-S015-002 — `consumedFields`를 redirect 경로와 동일 규칙으로 산출
- 유형: Functional / 우선순위: Must
- 설명: 합성 body의 `consumedFields`는 redirect 캡처 경로와 동일한 `consumedFields(responseBody)`
  (응답 최상위 키 ∩ `responseDtoFieldSets`)로 산출한다.
- 수용기준:
  - Given object-root 형상 body와 겹치는 `responseDtoFieldSets`, When 매칭 성공 기록, Then
    `consumedFields`가 redirect 경로 산출값과 동일(응답 키 ∩ DTO 필드).
  - Given collection(array-root) 형상 body, When 기록, Then `consumedFields`는 빈 리스트이고
    stub body는 array 전체(투영 비활성)로 비어있지 않다.
- 검증 레벨: integration

### REQ-S015-003 — 미매칭 호출은 stub 유지 + loud-fail (드롭 금지)
- 유형: Functional / 우선순위: Must
- 설명: callSite 매칭 실패·형상 부재·형상 합성 실패 시 호출을 드롭하지 않고 빈-body CAPTURED stub을
  유지하며 사유별 loud-fail을 기록한다.
- 수용기준:
  - Given 매칭되는 callSite 없음, When 처리, Then 빈-body CAPTURED `CapturedHttpCall` 유지 +
    `unmatched-external-call` loud-fail 기록.
  - Given 매칭되나 `responseShape` 없음, When 처리, Then 빈-body CAPTURED 유지 +
    `unwired-external-dep` loud-fail.
  - Given 형상 합성이 `UnsupportedShapeException`, When 처리, Then 빈-body CAPTURED 유지 +
    `unsynthesizable-shape` loud-fail.
- 검증 레벨: unit (EgressStubComposer) + integration

### REQ-S015-004 — 정적 인덱스 부재(callSites 빈) 시 기존 동작 보존
- 유형: Functional / 우선순위: Must
- 설명: `callSites`가 비어 있으면(none-mode/인덱스 없음) 기존 `EgressCallMapper.toCapturedHttpCall`
  빈-body CAPTURED 경로를 그대로 쓰고 loud-fail을 발생시키지 않는다(경고 노이즈 방지).
- 수용기준:
  - Given `callSites`가 빈 리스트, When egress 호출 처리, Then 결과는 기존과 동일한 빈-body CAPTURED
    이며 loud-fail이 추가되지 않는다.
- 검증 레벨: integration

### REQ-S015-005 — loud-fail 2-pass 중복 누적 방지 + dedup/redirect 우선 불변
- 유형: Functional / 우선순위: Must
- 설명: `captureHttpCalls`→`buildPaths`가 SQL 2-pass 보정으로 복수 실행돼도 동일 loud-fail이
  중복 누적되지 않으며, redirect-exchange와 egress의 `(method, urlPath)` dedup에서 redirect 우선
  규칙이 유지된다.
- 수용기준:
  - Given 동일 egress 호출이 2-pass로 두 번 처리, When loud-fail 수집, Then `externalLoudFails`에
    동일 항목이 중복 추가되지 않는다.
  - Given 같은 `(method, urlPath)`가 redirect-exchange와 egress 양쪽에 존재, When mergeDedup, Then
    redirect(existing) 항목이 유지되고 egress가 덮지 않는다.
- 검증 레벨: integration

### REQ-S015-006 — [E2E] redirect 없이 발견된 호출이 생성 테스트 stub으로 등록
- 유형: Functional / 우선순위: Must
- 설명: redirect 없이 span으로 발견된 외부 호출이 형상-시드 body를 가진 생성 테스트 stub
  등록 코드로 이어진다(otel·sleuth 양 모드).
- 수용기준:
  - **(generator 단위)** Given 비어있지 않은 형상 `responseBody`를 가진 egress `CapturedHttpCall`,
    When `HttpMockComposer.compose`, Then 방출 블록이 `scope.http().stub(method, urlPath) ...
    .respondJson(status, "<비어있지 않은 형상 body>") ... .register()`를 포함(빈 `""` 아님).
  - **(full E2E, 조건부)** Given otel `samples/order-service`(GET /inventory/stock) / sleuth
    `legacy-tram/order-web`(POST /reservations)를 외부 의존 직접 URL(WireMock 치환 아님)로 기동,
    When `BuilderCli.build`(otel/sleuth, `externalStubsDir=null`)→(선택)generator, Then graph
    `CapturedHttpCall`에 비어있지 않은 형상 body로 기록되고 생성 테스트 소스에 비어있지 않은 stub
    body가 포함. redirect/`--external-stubs` 미사용.
- 검증 레벨: integration(generator) + E2E black-box(조건부: sut.jar / `-Dsut.egress.sleuth=true`)

### REQ-S015-007 — 테스트 자원 정리 / 누수 검증 게이트
- 유형: Non-functional / 우선순위: Must
- 설명: SUT/도커를 띄우는 full E2E는 모든 종료 경로에서 자기 스코프만 teardown하고 잔존 0을 검증한다.
- 수용기준:
  - Given E2E가 SUT/compose 기동, When 성공·실패·예외·타임아웃 어느 경로든 종료, Then 고유
    project/label/PID 한정 teardown으로 컨테이너·네트워크·볼륨·프로세스 잔존 0(아니면 green 주장 금지).
  - Given 정리, Then `docker system prune`·광범위 `pkill` 미사용.
- 검증 레벨: process (E2E 하니스 검증)

### REQ-S015-008 — 선행 요구사항·문서 정합성 갱신
- 유형: Non-functional / 우선순위: Must
- 설명: 본 변경으로 구식이 되는 선행 요구사항·문서를 갱신해 spec↔code drift를 막는다.
- 수용기준:
  - Given REQ-005(egress 매핑은 항상 CAPTURED·빈 body)와 본 변경 충돌, When 갱신, Then egress
    요구사항 REQ-005 수용기준이 "성공 매칭 시 SYNTHESIZED·형상 body, 그 외 CAPTURED·빈 body"로
    정정되고, 그 문서의 REQ-015가 deferred→in-scope(본 명세 참조)로 활성화.
  - Given `docs/03-graph-rag-builder.md` 및 `2026-06-24-egress-span-capture-design.md` §2/§8의
    "발견까지/빈 body" 서술, When 갱신, Then 형상-시드 stub 등록까지로 최신화.
- 검증 레벨: process (문서 동기화 게이트)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-S015-001 | 매칭 성공→형상 body·SYNTHESIZED | `EgressStubComposerTest` + `CaptureHttpCallsEgressEnrichTest` | unit/integration | 🔴 planned |
| REQ-S015-002 | consumedFields redirect 동일·collection 빈 | `CaptureHttpCallsEgressEnrichTest` | integration | 🔴 planned |
| REQ-S015-003 | 미매칭 stub 유지 + loud-fail | `EgressStubComposerTest` | unit | 🔴 planned |
| REQ-S015-004 | callSites 빈 시 기존 동작 보존 | `CaptureHttpCallsEgressEnrichTest` | integration | 🔴 planned |
| REQ-S015-005 | 2-pass 중복 방지 + redirect 우선 | `CaptureHttpCallsEgressEnrichTest` | integration | 🔴 planned |
| REQ-S015-006 | [E2E] redirect 없이 생성 테스트 stub 등록 | `HttpMockComposerEgressTest` + `EgressStatusAgnosticStubE2E`(조건부) | integration/E2E | 🔴 planned |
| REQ-S015-007 | 자원 정리/누수 게이트 | `EgressStatusAgnosticStubE2E`(AfterAll teardown + 잔존 0) | process | 🔴 planned |
| REQ-S015-008 | 선행 요구사항·문서 정합성 갱신 | doc-sync 게이트(PR 전 점검) | process | 🔴 planned |

Coverage: 0/8 green (0%) — target 100% (대상: Must 8개). 연기/Won't 없음.

## 비고

- E2E full pipeline(REQ-S015-006 2번째 AC)은 SUT 빌드/도커 가용 시 조건부 실행이며, 무의존
  integration(generator 단위·빌더 in-process)이 1차 outer loop다(설계 §5.1). full E2E가 환경상
  unrunnable이면 그 사실을 명시하고 integration 레벨로 커버리지를 충족한다(원 egress E2E와 동일 관례).
- 본 명세는 design spec과 동일하게 3-벤더 design 리뷰 및 PR 문서동기화 게이트의 대상이다.
