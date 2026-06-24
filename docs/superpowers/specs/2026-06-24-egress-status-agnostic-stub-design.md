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
패키지: **`io.graphrag.builder.run`** — `CallSiteMatcher`·`ShapeJsonSynthesizer`·
`EndpointExplorationRunner.LoudFail`이 모두 이 패키지에 있어 패키지 사이클을 만들지 않는다
(`capture.egress`에 두면 run↔capture.egress 상호 의존 발생 — 3-벤더 리뷰 합의로 기각).

책임을 좁힌다: **match + body 합성만** 하고 `CapturedHttpCall` 조립은 호출자(`captureHttpCalls`)가
한다. 이렇게 해야 `consumedFields` 산출을 redirect 경로와 동일한 runner의
`consumedFields(responseBody)`로 일관되게 재사용할 수 있다(별도 클래스에서 private 메서드 접근 불가
문제 회피, Cursor 리뷰 I1).

```
// LoudFail = EndpointExplorationRunner.LoudFail (기존 nested record 재사용)
record Outcome(String responseBody,                       // 합성 성공 시 형상 JSON, 실패 시 ""
               CapturedHttpCall.Provenance provenance,    // 성공=SYNTHESIZED, 실패=CAPTURED
               Optional<LoudFail> loudFail)

static Outcome compose(EgressCall e,
                       List<ExternalCallSite> callSites,
                       ShapeJsonSynthesizer shapes)
```

로직:
1. `CallSiteMatcher.match(e.method(), e.path(), callSites)`.
2. site 없음 → `Outcome("", CAPTURED, LoudFail("unmatched-external-call", e.method()+" "+e.path()))`.
3. site 있고 `responseShape` 비어있음 → `Outcome("", CAPTURED,
   LoudFail("unwired-external-dep", e.method()+" "+e.path()))`.
4. site 있고 shape 있음 → `shapes.synthesizeBody(shape)`:
   - 성공 → `Outcome(body.toString(), SYNTHESIZED, Optional.empty())`.
   - `UnsupportedShapeException` → `Outcome("", CAPTURED,
     LoudFail("unsynthesizable-shape", site.pathLiteral()))`.

`compose`는 `static`이며 `EgressCall` 외 상태를 갖지 않는다.

### 4.2 `EndpointExplorationRunner` 변경

(a) **`private final ShapeJsonSynthesizer egressShapes` 필드 추가.** 현재 runner는
`ShapeJsonSynthesizer`를 필드로 보관하지 않고 `ExternalStubSynthesizer` 생성 시 인라인으로만 쓴다
(3-벤더 합의 I). egress enrichment는 **`httpCapture==null`(WireMock 미배선)에서도 동작해야 하므로**
`stubSynthesizer`에 의존하면 안 된다. canonical 생성자에서 `enumConstants`(null이면 `Map.of()`)로
`new ShapeJsonSynthesizer(...)`를 만들어 필드에 보관하고, egress/404 양쪽이 공유한다.

(b) **`captureHttpCalls`의 egress 루프를 교체.** 각 `EgressCall e`에 대해:
- `callSites`가 **비어 있으면**(정적 인덱스 없음/none-mode) 기존
  `EgressCallMapper.toCapturedHttpCall(e, pathId, seq)`를 그대로 쓴다 — **loud-fail 없음**
  (인덱스 부재 환경에서 호출마다 경고가 쏟아지는 노이즈 방지, Sonnet 리뷰 I3).
- 비어 있지 않으면 `EgressStubComposer.compose(e, callSites, egressShapes)`:
  - `responseBody`가 비어있지 않으면(성공) `consumedFields(responseBody)`(redirect 경로와 동일
    메서드)로 필드를 산출해 `CapturedHttpCall(id="http-{pathId}-egress-{seq}", pathId, e.method(),
    e.path(), Map.of(), null, status(e), responseBody, consumedFields, false, provenance)` 조립.
    `status(e)` = `e.statusOrNull()==null ? 200 : e.statusOrNull()`.
  - 실패(빈 body)면 `EgressCallMapper.toCapturedHttpCall(...)`(빈-body CAPTURED) 사용.
  - `outcome.loudFail()`이 있으면 수집한다. **단 `externalLoudFails`에 append하기 전 중복을
    검사**한다 — `captureHttpCalls`→`buildPaths`는 SQL 2-pass 보정에서 복수 실행될 수 있어 그대로
    append하면 동일 loud-fail이 누적된다(Gemini 리뷰 I5). `!externalLoudFails.contains(lf)` 가드.
- `EgressCallMapper.mergeDedup(calls, egress)`는 그대로 — redirect(existing) 우선 dedup 유지.

### 4.3 데이터 흐름
```
EgressCall(span) ─ EgressStubComposer.compose(match+synthesize) ─▶ captureHttpCalls 조립
                                                                     │ CapturedHttpCall(형상 body, SYNTHESIZED)
                                                                     │ (mergeDedup; redirect 우선)
                                                                     ▼
                                          graph httpCalls(pathId) ─▶ FileGraphRagClient.httpCallsForPath
                                                                     ▼
                                          HttpMockComposer ─▶ scope.http().stub(m,p).respondJson(status, 형상body).register()
```

### 4.4 에러 처리 (loud failure, silent 금지)
- unmatched callSite → WARN `unmatched-external-call` + 빈-body stub 유지.
- responseShape 없음 → WARN `unwired-external-dep` + 빈-body stub 유지.
- 형상 합성 불가(`UnsupportedShapeException`) → WARN `unsynthesizable-shape` + 빈-body stub 유지.
- 어느 경우에도 **발견된 호출을 graph/생성 테스트에서 드롭하지 않는다**(관측된 사실 보존).
- WARN 로깅 위치: `captureHttpCalls`가 `outcome.loudFail()` 수집 시 1회 로깅(composer는 순수 함수,
  로깅 부작용 없음 — 책임 분리, Cursor 리뷰 I7).

### 4.5 provenance 의미
span은 body를 포함하지 않는다. 매칭+합성된 body는 형상에서 만든 것이므로 `SYNTHESIZED`가 정확하다
— 근거는 `CapturedHttpCall.Provenance` enum Javadoc("실 SUT 외부 호출 캡처=CAPTURED vs 형상에서
합성한 stub=SYNTHESIZED"). 빈-body fallback은 CAPTURED 유지(실측도 합성도 아닌 미해결, loud-fail로
별도 가시화). ※ egress 요구사항의 REQ-011은 "자원 정리"이므로 provenance 근거로 인용하지 않는다
(Cursor 리뷰 I5).

### 4.6 `consumedFields`와 collection 형상
`shapes.synthesizeBody`는 `collection()==true` 형상에서 **ArrayNode**를 반환한다. 이 경우 runner의
`consumedFields(responseBody)`는 object root가 아니어서 빈 리스트를 돌려주고, `HttpMockComposer.
stubBody`도 ObjectNode가 아니면 필드 투영을 건너뛰어 array body 전체를 stub으로 쓴다. 따라서 array
형상도 **비어있지 않은 stub body**가 보장된다(투영만 비활성). object root 형상은 `consumedFields`
교집합 투영이 redirect 경로와 동일하게 적용된다.

## 5. 테스트 (이중 루프)

> **모듈 경계 주의(Cursor 리뷰 I3).** `HttpMockComposer`는 `test-generator` 모듈,
> `captureHttpCalls`/`EgressStubComposer`는 `graph-rag-builder` 모듈이다. 한 in-process 테스트로
> 빌더→generator를 잇지 않는다 — 빌더 측은 `CapturedHttpCall`까지, generator 측은 stub 방출까지
> 각 모듈에서 검증한다.

### 5.1 통합/E2E (outer, 먼저 red)
- **(빌더, 인프라 무의존 — 1차 outer loop)** `graph-rag-builder`에서 합성 `EgressCall` +
  `ExternalCallSite`(responseShape 보유)를 `captureHttpCalls`에 통과시켜, 결과 `CapturedHttpCall`이
  `responseProvenance==SYNTHESIZED` + `responseBody`가 비어있지 않은 형상 JSON임을 단언. callSites
  빈 경우 기존 빈-body CAPTURED 유지도 단언.
- **(generator)** `test-generator`에서 비어있지 않은 `responseBody`를 가진 egress
  `CapturedHttpCall`을 `HttpMockComposer.compose`에 넣어, 방출 블록이
  `respondJson(<status>, "<비어있지 않은 형상 body>")`를 포함함을 단언.
- **(full E2E, SUT 빌드 가용 시 — 최상위)** 기존 egress E2E 하니스는 **확장 대상이 아니다** —
  `OtelEgressDiscoveryE2E`/`SleuthEgressDiscoveryE2E`는 `EgressCollector.collect`까지만 검증하고,
  `Stage1ExternalStubSynthesisE2E`는 `EXTERNAL_INVENTORY_URL={{wiremock}}` 리다이렉트 전제다(Cursor
  리뷰 I4). 따라서 **신규 E2E 클래스**를 둔다: 외부 의존을 직접 URL(WireMock 치환 아님)로 두고
  `BuilderCli.build`(trace mode otel/sleuth, `externalStubsDir=null`) 실행 → 발견된 egress가
  graph `CapturedHttpCall`에 비어있지 않은 형상 body로 기록 → (선택) generator 실행해 생성 테스트
  소스에 비어있지 않은 stub body 포함을 단언. otel=`order-service`(GET /inventory/stock),
  sleuth=`legacy-tram/order-web`(POST /reservations, `-Dsut.egress.sleuth=true`·`order.web.src` 등
  기존 조건). 자원 정리/누수 게이트 준수(고유 project/PID 한정 teardown, 잔존 0; egress 요구사항
  REQ-011).

### 5.2 단위 (inner, TDD red→green)
`EgressStubComposer.compose`:
- 매칭+형상 → `SYNTHESIZED` + 비어있지 않은 body + 빈 loudFail.
- unmatched → `""` + CAPTURED + `unmatched-external-call` loudFail.
- 형상 없음 → `""` + CAPTURED + `unwired-external-dep` loudFail.
- `UnsupportedShapeException` → `""` + CAPTURED + `unsynthesizable-shape` loudFail.

`captureHttpCalls` 배선:
- callSites 비면 `EgressCallMapper` 경로(loud-fail 없음).
- 매칭 성공 시 `consumedFields`가 redirect 경로와 동일하게 산출됨.
- loud-fail이 `externalLoudFails`에 수집되며 2-pass 중복 append가 없음.
- redirect-exchange와 egress의 dedup에서 redirect 우선(기존 규칙) 불변.

## 6. 영향 / 위험
- `EgressCallMapper.toCapturedHttpCall`은 fallback로 잔존(삭제 안 함) — 빈-body 경로 재사용.
- generator/testlib **무변경** — 표면 회귀 위험 낮음.
- provenance가 매칭+합성된 egress 호출에서 CAPTURED→SYNTHESIZED로 바뀐다. **이는 egress 요구사항
  REQ-005의 현행 수용기준(egress 매핑은 항상 CAPTURED·빈 body)과 충돌**하므로 §7대로 요구사항을
  갱신해야 한다(Cursor 리뷰 I6). provenance 단언이 있는 기존 테스트가 있으면 갱신(구현 시 회귀 확인).
- `callSites`가 비어 있을 때는 기존 빈-body CAPTURED 경로를 유지하므로 none-mode/인덱스 부재 환경의
  동작·로그는 불변(loud-fail 노이즈 없음).
- 형상-시드 body가 빈 body를 대체하므로, 빈-body에 의존하던(없을 것으로 추정) 기존 동작은 변할 수
  있다 — 회귀 스위트로 확인.

## 7. 완료 정의
- 5.2 단위 + 5.1 통합/E2E 전부 green(요구사항명세 추적 매트릭스 100%, Must+미연기 Should 기준).
- 회귀 스위트 green + 자원 누수 0(자원 정리 게이트).
- **요구사항 갱신**(다음 단계 `requirements-spec`에서 수행):
  - REQ-015를 deferred→in-scope로 활성화하고 본 설계의 수용기준·추적 매트릭스 행을 추가.
  - egress 요구사항 REQ-005의 수용기준을 "성공 매칭 시 `responseProvenance=SYNTHESIZED`·형상 body,
    그 외 CAPTURED·빈 body"로 정정(현행은 항상 CAPTURED).
- **영향 문서 갱신**: `docs/03-graph-rag-builder.md`(egress가 "발견까지"→"형상-시드 stub 등록까지"),
  `docs/superpowers/specs/2026-06-24-egress-span-capture-design.md` §2/§8(1순위 body 빈 값 서술이
  구식이 됨), 본 spec, 요구사항명세.
