# 설계: REQ-015 — status-무관 stub register 경로 (egress 발견 → 형상-시드 stub 등록)

- 작성일: 2026-06-24
- 브랜치: `worktree-feat-egress-status-agnostic-stub` (base: origin/main `a8ee3742`)
- 선행 작업: 1순위 "트레이싱 기반 외부 HTTP egress 발견"(PR #95 머지, REQ-001~011)
- 관련 문서: `docs/superpowers/specs/2026-06-24-egress-span-capture-design.md`,
  `docs/superpowers/requirements/2026-06-24-egress-span-capture-requirements.md`(REQ-015가 🔵 deferred로 정의),
  `docs/03-graph-rag-builder.md`

## 1. 배경과 문제

1순위 작업으로 빌더는 이제 **리다이렉트 없이** OTEL/Brave CLIENT span에서 SUT의 외부 HTTP
호출을 발견한다. 발견된 호출 `EgressCall(method, path, statusOrNull, traceId)`는 요청 trace에
귀속되어 `EgressCallMapper.toCapturedHttpCall`로 graph `CapturedHttpCall`이 된다.

그러나 그 변환은 응답 형상 정보에 접근하지 못한다 — `EgressCallMapper`는 정적 인덱스
(`ExternalCallSite.responseShape`)를 모른다. 그 결과 span-발견 호출의 `CapturedHttpCall`은:

- `responseBody = ""` (빈 문자열)
- `consumedFields = []`
- `responseProvenance = CAPTURED`

로 기록된다. 이 호출은 graph에 영속되고, 생성 단계의 `HttpMockComposer.compose(...)`가
**모든** `CapturedHttpCall`에 대해 stub 등록 코드를 방출하므로 생성 테스트에는 이미

```java
scope.http().stub("GET", "/inventory/stock")
        .respondJson(200, "")     // ← 빈 body
        .register();
```

이 나온다. 즉 **등록 코드 자체는 나오지만 응답 body가 비어 있다.** 빈 body stub은 생성 테스트
실행 시 SUT의 외부 응답 역직렬화를 깨뜨릴 수 있다(외부 직후 분기 미개방).

반면 **404-driven 경로**(`EndpointExplorationRunner.synthesizeStubsForUnmatched`)는 WireMock이
unmatched로 돌려준 `status==404`를 트리거로 `CallSiteMatcher`→`ExternalCallSite.responseShape`
→`ShapeJsonSynthesizer`로 **형상 기반 minimal valid body**를 합성해 stub을 등록한다. 이 경로는
**base URL 리다이렉트를 전제**한다(WireMock이 외부 호출을 가로채야 404가 난다).

### 문제 정의

span-발견 호출은 404 시그널을 만들지 않으므로(리다이렉트 비의존) 형상-시드 body를 받지 못하고
빈 body로만 등록된다. **REQ-015는 404 시그널에 의존하지 않는(status-무관) 등록 경로를 추가해,
span-발견 호출도 callSite에 매칭해 형상-시드 body로 stub을 등록**하는 것이다.

## 2. 범위

### 포함 (in-scope)
- span-발견 `EgressCall`을 `CallSiteMatcher`로 `ExternalCallSite`에 매칭.
- 매칭되고 `responseShape`가 있으면 `ShapeJsonSynthesizer`로 형상-시드 body를 합성해
  `CapturedHttpCall(responseBody=형상body, responseProvenance=SYNTHESIZED,
  consumedFields=형상 최상위 필드)`로 기록.
- 매칭/형상/합성 실패 시 호출의 stub은 **유지**하되(빈 body, CAPTURED) loud-fail 로그로 surface
  (404 경로의 `unmatched-external-call`/`unwired-external-dep`/`unsynthesizable-shape`와 동형).
- 결과 body가 graph에 영속되어 기존 `HttpMockComposer`/generator가 그대로 형상 body stub을 방출.

### 제외 (out-of-scope)
- **응답 body 충실도**(실측 body·에러 계약 기반) — REQ-012(4순위, 별도). 본 작업의 body는
  **형상-시드(happy-minimal)** 까지다.
- **탐색 런타임에 SUT의 외부 호출로 stub 응답을 주입** — 리다이렉트 없이는 SUT가 WireMock을
  바라보지 않아 런타임 WireMock stub이 SUT에 무효하다. 이는 404-driven(리다이렉트) 경로의
  영역이며 그대로 둔다(두 경로 공존). 본 REQ는 리다이렉트를 **도입하지 않는다**.
  - ※ 이 범위 경계(생성 테스트 등록 경로만, 런타임 redirect 미도입)는 비서 inbox 결정 라우팅에서
    `status=deferred`(safe_default 적용)로 진행된 것으로, 사용자 검토에서 변경 가능하다.

## 3. 접근법 비교

### A. 빌더-측 enrichment (`captureHttpCalls`에서 매칭+합성) — **채택**
`EndpointExplorationRunner.captureHttpCalls`의 egress 매핑 루프에서, 각 `EgressCall`을 callSite에
매칭해 형상-시드 body를 합성하고 `CapturedHttpCall`을 형상 body·SYNTHESIZED로 빌드한다. 매칭/형상
실패는 빈-body CAPTURED 유지 + loud-fail. 합성 body는 graph에 영속되고 generator/testlib는 무변경.

- 장점: 외과적(빌더 단일 모듈), 기존 `CallSiteMatcher`+`ShapeJsonSynthesizer` 재사용,
  generator/testlib 미변경, body가 graph에 보이며 생성 테스트에 그대로 반영, 단위 테스트 용이.
- 단점: span stub body는 형상-시드까지(실측 body는 REQ-012). — 본 REQ 범위와 일치.

### B. generator-측 enrichment (`HttpMockComposer`에서 매칭+합성)
`CapturedHttpCall` body는 빈 채로 두고, `callSites`+`ShapeJsonSynthesizer`를 generator로
주입해 방출 시점에 합성.
- 단점: 모듈 경계 침범 — `callSites`/형상은 빌더 산출물이고 generator가 읽는 graph에 없다.
  callSite를 별도 영속하거나 배선해야 해 침습적. **기각.**

### C. 런타임 `ExternalStubSynthesizer.register`를 status-무관으로 호출
span-발견 호출에 대해 404 없이 `register()` 호출.
- 단점: 리다이렉트 없으면 WireMock stub이 탐색 중 SUT에 무효. `register()`는
  `CapturedHttpCall.responseBody`를 채우지 않으므로 생성 테스트 body 갭도 닫지 못한다.
  단독으로 REQ-015를 충족 못 함. **기각**(404 경로로서 이미 존재, 공존 유지).

## 4. 상세 설계 (접근 A)

### 4.1 신규 컴포넌트 — `EgressStubComposer` (builder)
패키지: `io.graphrag.builder.capture.egress` (또는 `...run`, 구현 시 응집도로 확정).

```
record Result(CapturedHttpCall call, Optional<LoudFail> loudFail)

Result compose(EgressCall e, String pathId, int seq,
               List<ExternalCallSite> callSites, ShapeJsonSynthesizer shapes)
```

로직:
1. `CallSiteMatcher.match(e.method(), e.path(), callSites)`.
2. site 없음 → `Result(빈-body CAPTURED CapturedHttpCall, LoudFail("unmatched-external-call",
   method+" "+path))`.
3. site 있고 `responseShape` 없음 → `Result(빈-body CAPTURED, LoudFail("unwired-external-dep", ...))`.
4. site 있고 shape 있음:
   - `JsonNode body = shapes.synthesizeBody(shape)` (UnsupportedShapeException →
     `Result(빈-body CAPTURED, LoudFail("unsynthesizable-shape", pathLiteral))`).
   - `CapturedHttpCall(id="http-{pathId}-egress-{seq}", pathId, e.method(), e.path(),
     query=Map.of(), requestBody=null, responseStatus=statusOrNull==null?200:statusOrNull,
     responseBody=body.toString(), consumedFields=형상 최상위 필드명,
     baggagePropagated=false, provenance=SYNTHESIZED)`.

빈-body fallback은 기존 `EgressCallMapper.toCapturedHttpCall`을 재사용한다(중복 제거).

### 4.2 `EndpointExplorationRunner.captureHttpCalls` 변경
egress 루프를 `EgressStubComposer.compose(...)` 호출로 교체:
- `ShapeJsonSynthesizer`는 runner가 이미 보유(stubSynthesizer 생성 시 `new
  ShapeJsonSynthesizer(enumConstants)` 사용). 동일 enum 맵으로 인스턴스 재사용/보관.
- 각 `Result.loudFail()`을 `externalLoudFails`에 수집(기존 loud-fail 집계와 동일 채널).
- `EgressCallMapper.mergeDedup(calls, egress)`는 그대로 — redirect(existing)이 egress보다
  우선하는 dedup 규칙 유지(REQ-005). 즉 redirect로 이미 잡힌 호출은 egress가 덮지 않는다.

### 4.3 데이터 흐름
```
EgressCall(span) ─ EgressStubComposer(match+synthesize) ─▶ CapturedHttpCall(형상 body, SYNTHESIZED)
                                                              │ (mergeDedup; redirect 우선)
                                                              ▼
                                       graph httpCalls(pathId) ─▶ FileGraphRagClient.httpCallsForPath
                                                              ▼
                                       HttpMockComposer ─▶ scope.http().stub(m,p).respondJson(status, 형상body).register()
```

### 4.4 에러 처리 (loud failure, silent 금지)
- unmatched callSite → WARN `unmatched-external-call` + 빈-body stub 유지.
- responseShape 없음 → WARN `unwired-external-dep` + 빈-body stub 유지.
- 형상 합성 불가(UnsupportedShapeException) → WARN `unsynthesizable-shape` + 빈-body stub 유지.
- 어느 경우에도 **발견된 호출을 graph/생성 테스트에서 드롭하지 않는다**(관측된 사실 보존).

### 4.5 provenance 의미
span은 body를 포함하지 않는다. 매칭+합성된 body는 형상에서 만든 것이므로 `SYNTHESIZED`가 정확하다
(REQ-011 의미: SYNTHESIZED=형상 합성, CAPTURED=실 외부 응답). 빈-body fallback은 기존대로 CAPTURED
유지(실측도 합성도 아닌 미해결 상태, loud-fail로 별도 가시화).

## 5. 테스트 (이중 루프)

### 5.1 E2E / 수용 (outer, 먼저 red)
- **otel**: `samples/order-service`(`InventoryClient`→`GET /inventory/stock`)를 redirect 없이
  egress 모드로 기동→탐색→generator 실행. **생성 테스트 소스**에
  `scope.http().stub("GET", "/inventory/stock") ... .respondJson(200, <비어있지 않은 형상 body>)
  ... .register()`가 포함됨을 단언(빈 `""`가 아님). redirect/`--external-stubs` 미사용.
- **sleuth**: `samples/legacy-tram/order-web`(`RestTemplate`→`POST /reservations`) 동형.
- 기존 `OtelEgressDiscoveryE2E`/`SleuthEgressDiscoveryE2E` 하니스(조건부: sut.jar /
  `sut.egress.sleuth=true`)를 확장해 generator 산출까지 잇는다. 자원 정리/누수 게이트(REQ-011)
  준수(고유 project/PID 한정 teardown, 잔존 0 검증).
- **practical outer loop(인프라 무의존)**: 합성 `EgressCall` + `ExternalCallSite`를
  `captureHttpCalls`→graph→`HttpMockComposer`로 통과시켜 방출 stub body가 비어있지 않은 형상
  body임을 단언하는 **통합 테스트**. 풀 E2E는 SUT 빌드/도커 가용 시 최상위로.

### 5.2 단위 (inner, TDD red→green)
`EgressStubComposer`:
- 매칭+형상 → SYNTHESIZED + 비어있지 않은 body + consumedFields(형상 필드).
- unmatched → 빈-body CAPTURED + `unmatched-external-call` loud-fail.
- 형상 없음 → 빈-body CAPTURED + `unwired-external-dep` loud-fail.
- UnsupportedShapeException → 빈-body CAPTURED + `unsynthesizable-shape` loud-fail.

`captureHttpCalls` 배선:
- loud-fail이 `externalLoudFails`에 수집됨.
- redirect-exchange와 egress의 dedup에서 redirect 우선(기존 규칙) 불변.

## 6. 영향 / 위험
- `EgressCallMapper.toCapturedHttpCall`은 fallback로 잔존(삭제 안 함) — 빈-body 경로 재사용.
- generator/testlib **무변경** — 표면 회귀 위험 낮음.
- provenance가 일부 egress 호출에서 CAPTURED→SYNTHESIZED로 바뀐다. provenance 단언이 있는
  기존 테스트가 있으면 갱신 필요(구현 시 회귀 확인).
- 형상-시드 body가 빈 body를 대체하므로, 빈-body에 의존하던(없을 것으로 추정) 기존 동작은 변할 수
  있다 — 회귀 스위트로 확인.

## 7. 완료 정의
- 5.2 단위 + 5.1 통합/E2E 전부 green(요구사항명세 추적 매트릭스 100%, Must+미연기 Should 기준).
- 회귀 스위트 green + 자원 누수 0(REQ-011 게이트).
- 영향 문서(`docs/03`, 본 spec, 요구사항명세) 갱신.
