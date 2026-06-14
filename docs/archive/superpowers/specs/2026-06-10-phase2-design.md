# 2026-06-10 — Phase 2 설계: WireMock 통합 (외부 HTTP)

기준: docs/09 (2.1~2.5), docs/06 (OTEL javaagent + baggage 격리), docs/04 (mock
격리 규칙), docs/03 (외부 응답은 "임베디드 mock의 minimal valid에서 출발").

## 목표 (roadmap 09)

외부 HTTP를 호출하는 endpoint의 생성 테스트가 WireMock을 사용해 통과하고
**병렬 안전**할 것.

## SUT 확장

`POST /api/orders`에 외부 의존 분기 추가: `type == "EXPRESS"`이면
`GET {EXTERNAL_INVENTORY_URL}/inventory/stock?type=...` (RestTemplate) 호출,
`available < amount` → 409. docs/04의 주문/재고 예제 재현.

## 단계별 설계

### 2.1 외부 HTTP 호출 추적

- **실행 캡처 우선**: 분석 환경에 임베디드 WireMock을 띄우고 SUT의 외부 URL을
  env로 리다이렉트(`--sut-env KEY={{wiremock}}` 치환). 요청 단위 journal delta로
  `CapturedHttpCall`(method/url/query/요청·응답 body) 회수.
- 정적 dataflow로 URL/body를 추적하는 원안은 보류 — 실행 캡처가 같은 사실을
  더 정확히 제공. 정적 분석은 2.5(응답 DTO 필드)와 literal 후보 추출에 한정.

### 2.2 임베디드 WireMock + 외부 스텁

- 도구는 외부 시스템 사양을 모름 → **minimal valid 응답은 운영자가
  `--external-stubs <dir>`(WireMock mapping JSON)로 제공** (docs/03의 전제).
- 탐색이 EXPRESS 분기에 도달하도록 **literal 후보 변이** 추가: handler 클래스의
  enum-스타일 문자열 리터럴(`[A-Z][A-Z0-9_]{1,15}`)을 String 필드 변이 후보로.

### 2.3 OTEL javaagent (분석 + 테스트 양쪽)

- 분석: SutProcess의 `JAVA_TOOL_OPTIONS`에 jacoco + otel agent 동시 부착,
  `OTEL_*` env (exporter none, propagators=tracecontext,baggage).
  invoker가 요청마다 `baggage: test-id=...`를 넣고 WireMock 수신 헤더로
  **전파 여부를 실측** → `CapturedHttpCall.baggagePropagated`.
- 테스트 실행: compose의 app 컨테이너에 동일 agent 부착 (docs/06 그대로).
- agent jar는 Gradle 의존성으로 확보 (런타임 다운로드 없음).

### 2.4 http-mock-composer (도구 2)

- testlib `HttpMockClient`에 stub 빌더 API 추가
  (`stub(method, path).withQueryParam(..).withBaggageTestId(..).respondJson(..).register()`)
  + `wiremock` 어댑터 (admin REST 직접 호출, 의존성 0; 스텁에 testId metadata를
  남겨 scope 단위 제거).
- 생성 테스트: path의 CapturedHttpCall마다 스텁 등록 라인 합성.
  baggage 매칭은 `baggagePropagated == true`일 때만.
- **병렬 안전 보고** (docs/04 규칙): http call 있고 propagation 없음 →
  `serial_required(SUT_PROPAGATION_MISSING)` + `@Execution(SAME_THREAD)` 마크.

### 2.5 응답 필드 사용 추적

- 정적 근사: SUT 소스에서 `getForObject/postForObject/exchange`의 응답 DTO
  타입 필드를 수집, 캡처 응답의 top-level 필드와 교집합 →
  `CapturedHttpCall.consumedFields`. 스텁 응답 body는 consumedFields만 투영
  (없으면 캡처 응답 전체).
- 콜그래프 기반 정밀 추적(실제 읽은 필드)은 보류 — DTO 바인딩 필드가 실용적 상한.

## shared-model 확장

- `CapturedHttpCall(id, pathId, method, urlPath, query, requestBody,
  responseStatus, responseBody, consumedFields, baggagePropagated)`
- `ExploredPath.capturedHttpCallIds` (후방 호환 normalize)
- `GraphAsset.httpCalls`

## 성공 기준

- [ ] 탐색이 EXPRESS 201 / 재고부족 409 path를 발견 (literal 변이 + large 값 변이)
- [ ] CapturedHttpCall에 url/query/응답 + baggagePropagated=true 기록
- [ ] 생성 테스트가 WireMock 스텁(baggage 매칭) 등록 후 compose에서 통과
- [ ] parallel_safety_report에 http-mock 사용 테스트가 fully_parallel로 분류
- [ ] 기존 Phase 0/1 테스트 비회귀 (`gradlew check` GREEN)
