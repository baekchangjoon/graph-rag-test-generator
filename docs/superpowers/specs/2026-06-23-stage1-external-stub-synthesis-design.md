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

## 문제

빌더는 SUT가 외부 HTTP 의존(결제·재고 등)을 호출할 때, 그 응답을 채우지 못하면 외부-의존 경로를 통과하지 못한다. 현재 채우는 방법은 `--external-stubs <dir>`로 **운영자가 WireMock mapping JSON을 수동 작성**하는 것뿐이다(`HttpCaptureServer.loadStubs`). stub이 없으면 외부 호출은 WireMock unmatched(404)를 받고, SUT가 그것을 에러로 처리하면 탐색이 그 지점에서 얕게 멈춘다.

그러나 **SUT 코드 자체가 응답 형상 계약을 들고 있다.** `restTemplate.getForObject(url, InventoryResponse.class)`의 역직렬화 타깃 `InventoryResponse`가 그것이다. 이 형상을 정적으로 추출하면, OpenAPI도 LLM도 없이 **minimal valid 응답을 결정적으로 합성**해 외부 호출 직후 분기까지 통과시킬 수 있다.

핵심 통찰: minimal valid 응답의 목적은 외부 서비스의 *정확한 계약 재현*이 아니라 "**SUT가 역직렬화에 성공하고 다음 분기로 넘어가게 하는 값**"이다. 이는 입력 측 `SampleInputSynthesizer`가 `@Valid` 가드를 통과하는 happy-path body를 만드는 것과 동형 문제다.

## 재사용 자산 (기존)

| 자산 | 기존 역할 | 단계1 역할 |
|---|---|---|
| `ResponseDtoIndexer` | 외부 응답 DTO의 필드명 집합 추출(2.5 근사) | **확장** — (method, pathLiteral, 응답 DTO FQN) 수집 |
| `BodyShapeExtractor.extract(model, fqn)` | 입력 DTO FQN → BodyShape(타입·중첩·컬렉션) | **그대로** — 응답 DTO FQN에 재적용 |
| `SampleInputSynthesizer` | BodyShape → minimal valid 입력 JSON(enum 첫 상수 등) | **값 규칙 공유** — 응답 body 합성(seed-row 로직 제외) |
| `SqlCaptureBackend.Scope` | 모드별(otel/sleuth/none) 요청 상관 헤더 발급 | **그대로** — 요청별 trace-id 소스 |
| `HttpCaptureServer` (WireMock) | stub 로드 + outbound 캡처 | **확장** — 런타임 stub 등록 + trace-id 귀속 + unmatched 기록 |

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
- `httpMethod`: 클라이언트 메서드명 매핑(`getForObject`/`getForEntity`→GET, `postForObject`/`postForEntity`→POST, `exchange`→인자의 `HttpMethod`).
- `responseShape`: `BodyShapeExtractor.extract(model, fqn)`. 응답 타입을 못 뽑는 형태(`ParameterizedTypeReference` 제네릭, WebClient `bodyToMono`, Feign 인터페이스 반환)는 `empty`.
- 기존 필드명 `Set<String>` 추출(consumedFields용)은 **유지**(회귀 없음). 신규 메서드를 별도로 추가.

#### 2. `TraceKey` (모드-중립 trace-id) — 신규

```java
interface TraceKey {
    java.util.Optional<String> readTraceId(java.util.Map<String,String> outboundHeaders);
}
```

- `OtelTraceKey`: `traceparent` 헤더(`00-<trace-id>-<span-id>-<flags>`)에서 trace-id 파싱.
- `SleuthTraceKey`: `X-B3-TraceId` 헤더 그대로.
- `NoTraceKey`: 항상 `empty`(none 모드).

활성 `--trace-mode`로 선택. 단계1은 **귀속**에만 쓴다. 단계2의 stub 격리(`withHeader(trace-id)`)도 이 추상화 위에 얹는다.

#### 3. `ExternalStubSynthesizer` — 신규 (핵심)

```java
JsonNode synthesizeBody(BodyShape shape);                 // 값 규칙 = SampleInputSynthesizer와 공유, seed-row 제외
void register(String method, String pathLiteral, JsonNode body);  // 200 + body → 런타임 addStubMapping
```

- `synthesizeBody`: `SampleInputSynthesizer`의 스칼라/enum/nested/collection 값 합성 규칙을 공유 헬퍼로 재사용. seed-row(DB 시드)는 응답에 무관하므로 제외.
- `register`: WireMock `urlPathEqualTo`/`urlPathPattern` + method 매칭, 200 + `application/json` + 합성 body. **멱등**(같은 (method, pathLiteral) 재등록 무시).
- 등록을 이 한 지점으로 모아, 단계2가 `register(..., traceId)` 오버로드로 격리를 얹게 둔다.

#### 4. `HttpCaptureServer` 확장

- 런타임 stub 등록 메서드 노출(`registerStub(StubMapping)` — 내부 `server.addStubMapping` 위임).
- `RawHttpExchange`에 `String traceId` 추가. `drainNewExchanges`가 outbound 헤더에서 `TraceKey.readTraceId`로 채움.
- 귀속: trace-id 있으면 그걸로(병렬-safe), 없으면(none) 기존 count-delta 폴백.
- **unmatched(404) 교환도 기록** — 어떤 외부 호출이 stub 없이 떨어졌는지 알아야 재주입 대상이 된다.

#### 5. 재탐색 루프 (`EndpointExplorationRunner` 내)

1차 invoke에서 unmatched 외부 호출 캡처 → 캡처 `(method, urlPath)`를 인덱싱 `pathLiteral`과 매칭 → `BodyShape` 확보 → 합성·등록 → **해당 endpoint 재invoke**.

- **수렴**: 새로 등록되는 stub이 없을 때까지, **상한 K회**(무한 방지, 기본 K=3 제안). 단계1은 1-hop 우선(외부 응답 직후 분기); 다단 연쇄는 K로 자연 커버하되 깊은 순서-의존은 비목표.
- **매칭 모호**(같은 method+path 복수 site): 첫 매치 + 로그.
- **멱등**: 이미 등록된 (method, pathLiteral)은 재합성/재등록하지 않는다.

#### 6. provenance 태깅

합성 stub으로 캡처된 `CapturedHttpCall`에 `responseProvenance = SYNTHESIZED` 메타를 단다(실제 외부 응답 = `CAPTURED`와 구분). 생성 테스트는 그 stub을 재현하되 응답값이 합성임을 표시 — 거짓 외부 계약 박제를 방지하되, SUT가 실제로 탐색한 분기이므로 테스트 자체는 보존한다.

### 결정성 (캐시 불필요)

형상-only 합성은 **순수 결정적**이라 `LlmOracle`가 필요로 했던 커밋 캐시·CI cache-or-skip이 전부 불필요하다:

- `BodyShapeExtractor`(Spoon 정적) · 값 규칙(Integer→0, String→`sample-<field>`, enum→정렬 첫 상수, nested/collection 재귀) — 입력 같으면 출력 같음.
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

`samples/order-service` (`InventoryClient` → `/inventory/stock`, `InventoryResponse(Integer available)`). 현재 `OtelHttpCaptureIntegrationTest`가 수동 stub으로 이 경로를 검증 중.

### E2E / 수용 테스트 (outer loop — 먼저 작성, red 유지)

out-of-process: 빌더 CLI를 실제 실행해 order-service를 띄우고 탐색.

1. **자동 합성으로 경로 통과** — `--external-stubs` 없이 빌드 → `InventoryClient` 외부 호출이 형상 합성 stub으로 200 수신 → 외부 직후 분기 커버리지 도달. 수동 stub 버전과 동등 커버리지.
2. **결정성** — 동일 commit 2회 → 동일 합성 stub(byte-동일) + 동일 커버리지.
3. **모드-중립(otel)** — `--trace-mode otel`에서 1 통과. (`sleuth` E2E는 sleuth 의존 SUT fixture가 없으면 unit으로 커버.)
4. **loud-fail surface** — 응답 타입 미추출 호출 site를 가진 fixture에서 리포트에 `unwired-external-dep ... fallback=stage3` 기록.
5. **provenance** — 합성 stub으로 캡처된 `CapturedHttpCall`에 `responseProvenance=SYNTHESIZED`.

### Unit 테스트 (inner loop — TDD red→green)

- `ResponseDtoIndexer` 확장: `(method, pathLiteral, BodyShape)` 추출 / 미추출 형태 → `empty`.
- `TraceKey`: otel `traceparent` 파싱, sleuth `X-B3-TraceId`, none `empty` — sleuth 모드 trace-id 추출을 여기서 커버.
- `ExternalStubSynthesizer`: `BodyShape → JSON`(Integer→0, enum→정렬 첫 상수, nested/collection 재귀) + `register` 멱등.
- `drainNewExchanges`: trace-id 귀속(병렬-safe 시뮬), unmatched(404) 기록.
- 재탐색 수렴: K 상한, 중복 등록 방지, "새 stub 없으면 종료".

### 완료 정의

- 모든 단계1 E2E green + unit green.
- 요구사항명세 REQ-ID 추적 매트릭스 100%(Must + 미연기 Should).
- 게이트: spec-compliance + code-quality 리뷰, 회귀 green, 문서 동기화.

## 리뷰 반영

(3벤더 design-doc 리뷰 후 채움)
