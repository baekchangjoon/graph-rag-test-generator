# 단계1 — 형상-only 외부 응답 stub 합성 (설계)

- 일자: 2026-06-23
- 브랜치: feat-external-stub-synthesis
- 관련 로드맵: [docs/27](../../27-roadmap-otel-capture-stub-seeding.md) ② "외부 stub seeding"의 무-LLM·무-OpenAPI 1순위 구현
- 결정 경로: 대화형 brainstorming(B2 캡처→재주입, 옵션 X 모드-중립 trace-id 격리, provenance (c) 태깅)을 사용자가 섹션별로 승인

## 비목표 경계 (먼저 못박는다)

이 작업은 **형상(shape)에서 결정적으로 합성한 minimal valid 응답 1종으로 외부-의존 경로를 통과**시키는 것이다. 다음은 명시적 비목표다:

- **응답 값 변형 fuzzing** — 같은 외부 호출에 enum 상수 조합·status 리터럴·숫자 경계 등 *여러 변형*을 갈아끼우며 분기를 여는 것은 **단계2**다. 단계1은 변형 루프·budget·커버리지 유도를 만들지 않는다.
- **LLM / OpenAPI 응답 합성** — **단계3**. 단계1이 응답 형상을 못 뽑는 호출(제네릭/WebClient/Feign)은 합성하지 않고 기록만 한다.
- **병렬 탐색 실행** — 단계1은 직렬 실행을 유지한다. 단 메커니즘은 trace-id 격리로 **병렬-safe하게 설계**한다(실행은 후속).

**모드 범위**: 단계1은 **analysis 모드 우선**(임베디드 WireMock = `AnalysisEnvironment`). attach 모드는 기존 `--external-stubs`/`--sut-env` 배선이 동작하나(`BuilderCli.runAttached`), attach의 per-run token path-prefix 정규화(`HttpCaptureServer`의 `hostBaseUrl`/token)가 런타임 register·match에 미치는 영향은 **별도 검증**으로 둔다. 1차 구현·E2E는 analysis 기준, attach 호환은 "회귀 없음" 수준으로 확인.

## 문제

빌더는 SUT가 외부 HTTP 의존(결제·재고 등)을 호출할 때, 그 응답을 채우지 못하면 외부-의존 경로를 통과하지 못한다. 현재 채우는 방법은 `--external-stubs <dir>`로 **운영자가 WireMock mapping JSON을 수동 작성**하는 것뿐이다(`HttpCaptureServer.start()`가 stubsDir에서 초기 로드). stub이 없으면 외부 호출은 WireMock unmatched(404)를 받고, SUT가 그것을 에러로 처리하면 탐색이 그 지점에서 얕게 멈춘다.

그러나 **SUT 코드 자체가 응답 형상 계약을 들고 있다.** `restTemplate.getForObject(url, InventoryResponse.class)`의 역직렬화 타깃 `InventoryResponse`가 그것이다. 이 형상을 정적으로 추출하면, OpenAPI도 LLM도 없이 **minimal valid 응답을 결정적으로 합성**해 외부 호출 직후 분기까지 통과시킬 수 있다.

핵심 통찰: minimal valid 응답의 목적은 외부 서비스의 *정확한 계약 재현*이 아니라 "**SUT가 역직렬화에 성공하고 다음 분기로 넘어가게 하는 값**"이다. 이는 입력 측 `SampleInputSynthesizer`가 `@Valid` 가드를 통과하는 happy-path body를 만드는 것과 동형 문제다.

## 재사용 자산 (기존)

| 자산 | 기존 역할 | 단계1 역할 |
|---|---|---|
| `ResponseDtoIndexer` | 외부 응답 DTO의 필드명 집합 `List<Set<String>>` 추출(2.5 근사, consumedFields용) | **신규 메서드 추가**(기존 유지) — (method, pathLiteral, 응답 DTO FQN) 수집; 배열 타입(`Dto[].class`)은 `[]` strip 후 component 타입 해석 |
| `BodyShapeExtractor.extract(model, fqn)` | 입력 DTO FQN → BodyShape(타입·중첩·컬렉션) | **그대로** — 응답 DTO FQN에 재적용 |
| `SampleInputSynthesizer` 값 규칙 | BodyShape → minimal valid 입력 JSON(현재 합성 헬퍼는 **private**: `scalarValue`/`boundedInt`/`applySize` 등, seed-row·Bean Validation과 결합) | **공유 헬퍼 추출 필요** — seed/validation 제외한 `ShapeJsonSynthesizer`(신규, `io.graphrag.builder.run`)로 값 규칙을 추출해 입력·응답이 공유. 기존 `SampleInputSynthesizer`는 위임 구조로 동작 보존 |
| `SqlCaptureBackend.Scope` | 모드별(otel/sleuth/none) 요청 상관 헤더를 **빌더→SUT inbound 요청에 주입** | **그대로** — inbound 주입 전용. outbound trace-id를 ServeEvent에서 *읽는* 책임은 Scope에 없음 → 아래 `TraceKey`(신규)가 담당 |
| `HttpCaptureServer` (WireMock) | start() 시 stub 로드 + outbound 캡처(현재 `baggage` 헤더만 읽음, status 포함 모든 ServeEvent 기록) | **확장** — 런타임 stub 등록 + outbound trace-id 추출(신규) + unmatched(404) 재주입 분류 |

## 설계

### 핵심 데이터 흐름 (B2 캡처 → 재주입 루프)

```
[인덱싱]   ResponseDtoIndexer 확장
             → 호출 site별 (httpMethod, pathLiteral, 응답 DTO FQN)
             → BodyShapeExtractor.extract(FQN) → Optional<BodyShape>

[1차 invoke]  stub 없음 → SUT 외부 호출 → WireMock unmatched(404)
             → drainNewExchanges가 outbound trace-id로 귀속(병렬-safe)
             → 캡처된 (method, urlPath)를 인덱싱 pathLiteral과 매칭 → BodyShape 확보

[합성]     ExternalStubSynthesizer.synthesizeBody(BodyShape)
             → minimal valid JSON (Integer→0, String→sample, enum→정렬 첫 상수, nested/collection 재귀)

[등록]     ExternalStubSynthesizer.register(method, pathLiteral, body)
             → 200 + body → HttpCaptureServer 런타임 addStubMapping (urlPathPattern)

[2차 invoke]  해당 endpoint 재탐색 → 외부 호출이 합성 응답(200) 수신
             → 외부 직후 분기 진입 → 커버리지 delta 측정
```

### 컴포넌트 분해

모두 `io.graphrag.builder` 안. LLM/HTTP 신규 의존 없음(기존 WireMock·Spoon만).

#### 1. `ResponseDtoIndexer` 확장 (인덱싱)

현재 `getForObject(..., X.class)`에서 응답 DTO 필드명 `Set<String>`만 뽑는다. 확장:

```java
record ExternalCallSite(String httpMethod, String pathLiteral, java.util.Optional<BodyShape> responseShape) {}
List<ExternalCallSite> extractCallSites(CtModel model);
```

- `pathLiteral`: URL 인자의 정적 리터럴 구간 추출(`baseUrl + "/inventory/stock?type=" + type` → `/inventory/stock`). 동적 query/baseUrl은 제외.
- `httpMethod`: 클라이언트 메서드명 매핑(`getForObject`/`getForEntity`→GET, `postForObject`/`postForEntity`→POST). `exchange(...)`는 인자의 `HttpMethod` enum 상수를 정적 추출하되, **인자가 변수/필드 참조이면 추출 불가 → `empty` + `unextractable-response-type` WARN**.
- `responseShape`: `BodyShapeExtractor.extract(model, fqn)`. **배열 타입**(`Dto[].class`)은 FQN의 `[]`를 strip하고 component 타입으로 해석(BodyShape.collection=true). 응답 타입을 못 뽑는 형태(`ParameterizedTypeReference` 제네릭, WebClient `bodyToMono`, Feign 인터페이스 반환)는 `empty`.
- 기존 `List<Set<String>>` 필드명 추출(consumedFields용)은 **유지**(회귀 없음). 신규 메서드를 별도로 추가하되 **`SharedSpoonModel`을 공유**해 Spoon 파싱이 1회만 일어나게 한다.
- **파급(명시)**: `extractCallSites` 결과를 탐색 루프까지 전달하려면 `BuilderCli.StaticIndexBundle` 레코드에 `List<ExternalCallSite> callSites` 필드를 추가하고, `indexStatically()`에서 채워 `explore()` → `EndpointExplorationRunner`(재탐색 루프)로 전달한다. 기존 `responseDtoFieldSets`(consumedFields) 경로는 그대로 둔다.

#### 2. `TraceKey` (모드-중립 trace-id) — 신규

```java
interface TraceKey {
    java.util.Optional<String> readTraceId(java.util.Map<String,String> outboundHeaders);
}
```

- `OtelTraceKey`: `traceparent` 헤더(`00-<trace-id>-<span-id>-<flags>`)에서 trace-id 파싱.
- `SleuthTraceKey`: `X-B3-TraceId` 헤더 그대로.
- `NoTraceKey`: 항상 `empty`(none 모드).

활성 `--trace-mode`로 선택. **이것은 신규 추상화다** — `SqlCaptureBackend.Scope`(inbound 주입)와 별개로, WireMock `ServeEvent.getRequest().getHeaders()`(outbound)에서 trace-id를 *읽는* 책임을 진다. 단계1은 **귀속**에만 쓴다. 단계2의 stub 격리(`withHeader(trace-id)`)도 이 추상화 위에 얹는다.

**전제(spec 명시 + 1차 spike 실측)**: otel 모드에서 `Scope`가 inbound로 주입한 traceparent를 OTel agent의 RestTemplate 계측이 **outbound 외부 호출로 전파**한다(baggage가 이미 outbound에 실려오는 것으로 보아 같은 propagator 경로로 traceparent도 전파될 것이나, 구현 1차에 실측 확인). 전파가 확인 안 되면 none 모드와 동일한 count-delta 폴백.

#### 3. `ExternalStubSynthesizer` — 신규 (핵심)

```java
JsonNode synthesizeBody(BodyShape shape);                 // 값 규칙 = SampleInputSynthesizer와 공유, seed-row 제외
void register(String method, String pathLiteral, JsonNode body);  // 200 + body → 런타임 addStubMapping
```

- `synthesizeBody`: 값 합성 규칙은 **`ShapeJsonSynthesizer`(신규 공유 헬퍼)** 를 통해 입력 측과 공유한다 — `SampleInputSynthesizer`의 현재 private 헬퍼(`scalarValue`/`boundedInt`/`applySize`/nested·collection 재귀)를 seed-row·Bean Validation·`fieldConstraints` 의존 없이 추출. 응답 합성은 table/fieldConstraints 없이 호출. **실제 기본값**(정정): `Integer`→`1`(`boundedInt`), `String`→`sample-<field>`(또는 `sample`), `enum`→정렬 첫 상수, `Boolean`→`false`. (이전 표기 "Integer→0"은 부정확.)
- `register`: WireMock `urlPathEqualTo`/`urlPathPattern` + method 매칭, 200 + `application/json` + 합성 body. **멱등**(같은 (method, pathLiteral) 재등록 무시).
- 등록을 이 한 지점으로 모아, 단계2가 `register(..., traceId)` 오버로드로 격리를 얹게 둔다.
- 추출 리팩터는 `SampleInputSynthesizer`의 기존 단위 테스트가 그대로 green이어야 한다(위임, 동작 보존).

#### 4. `HttpCaptureServer` 확장

- 런타임 stub 등록 메서드 노출(`registerStub(StubMapping)` — 내부 `server.addStubMapping` 위임). 시작 시 `loadStubs`와 동일 경로, 호출 시점만 런타임.
- `RawHttpExchange`에 `String outboundTraceId` 추가. `drainNewExchanges`는 현재 `baggage` 헤더만 읽으므로(`HttpCaptureServer.java:127`), `ServeEvent.getRequest().getHeaders()`에서 활성 모드 헤더(traceparent/X-B3-TraceId)를 **추가 추출**해 `TraceKey.readTraceId`로 채운다(신규 로직).
- **레코드 파급(명시)**: `RawHttpExchange` 필드 추가 → `InvocationOutcome`·`captureHttpCalls`(`EndpointExplorationRunner:1369`)·`CapturedHttpCall` 변환 체인을 함께 갱신.
- 귀속: trace-id 있으면 그걸로(병렬-safe). **none 모드 귀속 정책**: 단계1은 직렬 실행이므로 1차 invoke 직후 `drainNewExchanges()` 결과 전체를 해당 요청의 unmatched로 본다. 이 전제가 깨지는 상황(비동기/지연 응답)은 귀속 오류 가능성을 WARN으로 기록.
- **unmatched 분류(정정)**: `drainNewExchanges`는 이미 status 포함 모든 ServeEvent를 기록한다 — 신규는 "기록"이 아니라 **status==404(unmatched)를 재주입 큐에 분류**하고, 이미 등록된 stub의 200 응답과 구분하는 필터다.

#### 5. 재탐색 루프 (`EndpointExplorationRunner` 내)

기존 SQL pass-2와 같은 **endpoint-scoped 재시도 루프**다. 의사코드:

```
for endpoint in endpoints:
    outcome = invoke(endpoint)                       # 1차
    round = 0
    while round < K:
        unmatched = outcome.httpExchanges.filter(status == 404)   # 이 요청의 미충족 외부 호출
        newly = []
        for ex in unmatched:
            site = matchCallSite(ex.method, ex.urlPath)           # 아래 매칭 규칙
            if site.responseShape.isEmpty(): log unwired/unmatched; continue
            if alreadyRegistered(site): continue                  # 멱등
            body = synthesizer.synthesizeBody(site.responseShape)
            synthesizer.register(site.method, site.pathLiteral, body)
            newly += site
        if newly is empty: break                                 # 수렴
        coverage.resetBaseline(); outcome = invoke(endpoint)      # stub 적용 후 재invoke, 커버리지 baseline 리셋
        round += 1
```

- **매칭 규칙**(`matchCallSite`): WireMock 캡처 `urlPath`(query strip 후, `HttpCaptureServer.java:109`)와 인덱싱 `pathLiteral`을 **segment 경계 기준 `endsWith`** 로 비교(동적 baseUrl은 path 부분만 남으므로). 복수 매치는 **path 길이가 가장 긴 것 우선**(specificity), 동률이면 첫 매치 + WARN. method도 일치해야 함.
- **수렴**: 새로 등록되는 stub이 없으면 종료, **상한 K회**(무한 방지, 기본 K=3 — `--external-stub-max-rounds`로 override 여부는 plan에서 결정). 단계1은 1-hop 우선(외부 응답 직후 분기); 다단 연쇄는 K로 자연 커버하되 깊은 순서-의존은 비목표.
- **멱등**: 이미 등록된 (method, pathLiteral)은 재합성/재등록하지 않는다. 재invoke 시 stub 등록 상태는 누적(WireMock 서버 인스턴스에 유지), 커버리지 baseline만 리셋해 delta를 깨끗이 측정.

#### 6. provenance 태깅

합성 stub으로 캡처된 `CapturedHttpCall`에 `responseProvenance` 필드(enum `CAPTURED`/`SYNTHESIZED`)를 단다.

- **하위 호환(명시)**: `CapturedHttpCall`은 shared-model 레코드다. 필드 추가 시 (a) 기존 생성자 호출부가 깨지지 않게 **compat 생성자**(기존 시그니처 → `CAPTURED` 기본)를 두고, (b) Jackson 역직렬화는 레거시 graph.json(필드 없음)을 `CAPTURED`로 기본 처리(`GraphAsset` 선례 참고). `JsonRoundTripTest`를 확장해 레거시 JSON 호환을 검증.
- **단계1 범위 경계**: 단계1은 **빌더 측 `CapturedHttpCall` 메타까지**다. 생성 테스트 코드에 합성 표식(주석/마커)을 다는 것은 test-generator 측 후속 작업(범위 밖). 그 메타를 소비해 거짓 외부 계약 박제를 구분하는 것이 목적이며, SUT가 실제로 탐색한 분기이므로 테스트 자체는 보존한다.

### 결정성 (캐시 불필요)

형상-only 합성은 **순수 결정적**이라 외부 의존성·캐시·CI 분기가 전부 불필요하다(no-LLM·재현성 원칙에 그대로 부합):

- `BodyShapeExtractor`(Spoon 정적) · 값 규칙(Integer→1, String→`sample-<field>`, enum→정렬 첫 상수, nested/collection 재귀) — 입력 같으면 출력 같음.
- trace-id는 `TraceParent`가 runId 시드 + 단조 카운터 SHA-256으로 발급 → 이미 결정적.
- call site 순회·stub 등록 순서 정렬.

→ 동일 commit → 동일 stub(byte-동일), CI 포함 오프라인 완전 재현. 외부 의존성 0.

### loud-fail (silent 금지)

"외부 호출이 404로 얕게 끝남"이 조용히 묻히지 않게, 아래는 전부 WARN 로그 + 빌드 리포트 기록:

1. **미추출 응답 타입**(제네릭/WebClient/Feign) → `unwired-external-dep: <method path>, reason=unextractable-response-type, fallback=stage3`.
2. **매칭 실패**(캡처 URL ↔ 인덱싱 pathLiteral 대응 없음) → `unmatched-external-call: <method path>`.
3. **복잡/재귀 형상 합성 불가** → `unsynthesizable-shape: <FQN>`.
4. **합성 stub 등록 후에도 다음 분기 미도달**(역직렬화 실패 등) → `stub-ineffective: <path>`.

### 엣지 케이스 · 한계 (명시)

- **값-의존 분기**: 형상-only는 enum을 정렬 첫 상수로만 채워 1 arm만 통과. 나머지 arm은 **단계2의 enum 상수 조합(결정적, no-LLM)으로 전부 열림**. 자유 String/숫자 값-의존은 단계2 후속 소스(리터럴/concolic) 또는 단계3 대상.
- **trace-mode none + 병렬**: 격리 불가 → 직렬만. 단계1은 직렬 실행이라 무방.
- **합성 형상 범위(YAGNI)**: `SampleInputSynthesizer`가 커버하는 형상(스칼라/enum/단순 nested/collection)만. 복잡·재귀·제네릭 형상은 skip + `unsynthesizable-shape` 기록(generic 빌더+Instancio 머지 시 후속 확장).
- **다단 연쇄**(외부→외부): K회로 자연 커버, 깊은 순서-의존은 비목표.

## 테스트 전략 (double-loop TDD)

### 검증 SUT

`samples/order-service` (`InventoryClient` → `/inventory/stock`, `InventoryResponse(Integer available)`).

**벤치마크 정정**: `OtelHttpCaptureIntegrationTest`는 system property `external.stubs`로 inventory stub을 로드하지만 본문은 OTEL SQL 캡처·trace 귀속이 중심이라 inventory 경로 **커버리지 동등성을 단언하지 않는다**. 단계1 E2E의 "수동 stub 대비 동등 커버리지" 벤치마크는 **수동 stub + EXPRESS `CapturedHttpCall`을 단언하는 통합 테스트(예: `BuilderIntegrationTest` 계열) 또는 신규 단계1 E2E**를 기준으로 삼는다. `OtelHttpCaptureIntegrationTest`는 OTEL 인프라 fixture로만 참조한다.

### E2E / 수용 테스트 (outer loop — 먼저 작성, red 유지)

out-of-process: 빌더 CLI를 실제 실행해 order-service를 띄우고 탐색.

1. **자동 합성으로 경로 통과** — `--external-stubs` 없이 빌드 → `InventoryClient` 외부 호출이 형상 합성 stub으로 200 수신 → 외부 직후 분기 커버리지 도달. 수동 stub 버전과 동등 커버리지.
2. **결정성** — 동일 commit 2회 → 동일 합성 stub(byte-동일) + 동일 커버리지.
3. **모드-중립(otel)** — `--trace-mode otel`에서 1 통과. (`sleuth` E2E는 sleuth 의존 SUT fixture가 없으면 unit으로 커버.)
4. **loud-fail surface** — 응답 타입 미추출 호출 site를 가진 fixture에서 리포트에 `unwired-external-dep ... fallback=stage3` 기록.
5. **provenance** — 합성 stub으로 캡처된 `CapturedHttpCall`에 `responseProvenance=SYNTHESIZED`.

### Unit 테스트 (inner loop — TDD red→green)

- `ResponseDtoIndexer` 확장: `(method, pathLiteral, BodyShape)` 추출 / 미추출 형태(제네릭·WebClient·Feign·exchange 변수인자) → `empty` / 배열 `Dto[].class` → component 해석.
- `ShapeJsonSynthesizer`(신규 공유 헬퍼): `BodyShape → JSON`(Integer→1, String→`sample-<field>`, enum→정렬 첫 상수, nested/collection 재귀). **`SampleInputSynthesizer` 기존 단위 테스트 green 유지**(위임, 동작 보존).
- `TraceKey`: otel `traceparent` 파싱, sleuth `X-B3-TraceId`, none `empty` — sleuth 모드 trace-id 추출을 여기서 커버.
- `ExternalStubSynthesizer.register` 멱등 + `urlPathPattern`/method 매칭.
- `drainNewExchanges`: trace-id 귀속(병렬-safe 시뮬), unmatched(404) 재주입 분류.
- `matchCallSite`: segment-경계 `endsWith` + specificity(최장 path) + method 일치.
- 재탐색 수렴: K 상한, 중복 등록 방지, "새 stub 없으면 종료", 재invoke 시 커버리지 baseline 리셋.
- `CapturedHttpCall` 호환: `JsonRoundTripTest` 확장 — 레거시 JSON(필드 없음) → `CAPTURED` 역직렬화.

### 완료 정의

- 모든 단계1 E2E green + unit green.
- 요구사항명세 REQ-ID 추적 매트릭스 100%(Must + 미연기 Should).
- 게이트: spec-compliance + code-quality 리뷰, 회귀 green, 문서 동기화.

## 대안 검토 (왜 형상-only 정적 합성인가)

- **OpenAPI 파싱**(로드맵 27-② 원안): 외부 서비스의 ground-truth 계약이나, 스펙을 제공하지 않는 내부 서비스가 많아 적용 범위가 좁다 → 단계3으로 이전.
- **LLM 응답 합성**: 도메인 의미값엔 강하나 비결정적(캐시 필요)·외부 의존 → 단계3.
- **동적 mock 프레임워크**(자동 mock 생성): 형상 추론을 외부 라이브러리에 위임하나, SUT 역직렬화 타깃(`X.class`)이 이미 형상을 주므로 불필요한 의존.
- **선택: 형상-only 정적 합성** — SUT 코드만으로 동작(적용 범위 최대), 순수 결정적(no-LLM·재현성), 기존 자산(`BodyShapeExtractor`+값 규칙) 재사용.

## 리뷰 반영

3벤더 design-doc 리뷰(Claude Sonnet / Gemini 3.5 Flash High / Cursor auto) 결과를 반영:

- **반영**: TraceKey를 `Scope` 재사용이 아닌 신규 추상화로 분리하고 `drainNewExchanges`가 현재 `baggage`만 읽음을 명시(Sonnet I1/I2·Cursor I10) · `ShapeJsonSynthesizer` 공유 헬퍼 추출(현 헬퍼 private)(Sonnet I3·Cursor I8) · `CapturedHttpCall` 레코드 호환(compat 생성자+Jackson 기본 CAPTURED+JsonRoundTrip)(Sonnet I9·Gemini I3) · `StaticIndexBundle`/`explore` 파급 + SharedSpoonModel 1회 파싱(Sonnet I4) · 배열 응답 DTO `[]` strip(Gemini I2) · path 매칭 알고리즘(segment endsWith+specificity)(Sonnet I5·Gemini I1) · 실제 기본값 정정 Integer→1(Gemini I4) · 검증 벤치마크를 `OtelHttpCaptureIntegrationTest`에서 `BuilderIntegrationTest`/신규 E2E로 정정(Cursor I13) · attach/analysis 모드 범위 명시(Cursor I7) · 재탐색 의사코드+커버리지 baseline 리셋(Cursor I6) · none 모드 귀속 정책(Sonnet I6) · exchange HttpMethod 변수인자 한계(Sonnet I7) · loadStubs/unmatched 용어 정정(Cursor I11/I12) · LlmOracle 언급 제거(repo 부재)(Cursor I9) · 대안 검토 섹션(Gemini I7).
- **부분 기각**: 생성 테스트의 provenance markup 구체 문법(Gemini I5) — 단계1은 빌더 측 `CapturedHttpCall` 메타까지가 범위, 생성 코드 표식은 test-generator 후속. `--external-stub-max-rounds` CLI 옵션(Gemini I6) — 도입 여부는 plan에서 결정(기본 K=3 고정으로 충분할 수 있음, YAGNI).

## 로드맵 역전파 (구현 후)

`docs/27` §2(OpenAPI 기반 외부 stub seeding)에 "무-LLM·무-OpenAPI 1순위는 본 단계1이 커버, OpenAPI 접근은 단계3으로 이전" 상태 주석을 역전파한다(spec↔로드맵 drift 방지).
