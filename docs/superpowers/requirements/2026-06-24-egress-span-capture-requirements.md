# 트레이싱 기반 외부 HTTP egress 발견 요구사항명세
> 출처(design spec): docs/superpowers/specs/2026-06-24-egress-span-capture-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 🟢)

## 범위 메모
- 적용 대상: 외부 관측 행위 변경(외부 호출 발견 산출 + 신규 수신기 + SUT env 배선) → 요구사항명세 작성.
- 산출물 경계: 이 작업은 **발견 레코드(`CapturedHttpCall`)**까지. redirect 없이 stub 등록까지는 별도 REQ(🔵).
- 코드 사실(검증됨): `CapturedHttpCall(id, pathId, method, urlPath, query, requestBody, **responseStatus**(int), responseBody, consumedFields, **baggagePropagated**(boolean), **responseProvenance**(Provenance{CAPTURED,SYNTHESIZED}))`. `TraceKey.forMode(String).readTraceId(Map outboundHeaders)`(인스턴스). `OtlpTraceReceiver`의 `MAX_TRACES=50_000`/`MAX_SPANS_PER_TRACE=10_000`/`HEX_32`는 현재 private.

## 요구사항 목록

### REQ-001 — otel 모드 egress CLIENT span 추출
- 유형: Functional / 우선순위: Must
- 설명: otel 모드에서 `OtlpTraceReceiver`에 누적된 한 trace의 span 중 외부 HTTP 호출 span을 추출한다.
- 수용기준:
  - Given 한 trace의 span 집합(DB/SERVER/CLIENT 혼재), When 추출, Then `kind == "SPAN_KIND_CLIENT"`이고 `http.request.method` 또는 `http.method` 속성을 가진 span만 선택된다.
  - Given DB CLIENT span(`db.query.text`만, http 속성 없음), When 추출, Then 제외된다.
- 검증 레벨: integration + unit

### REQ-002 — sleuth/Brave 모드 egress CLIENT span 추출
- 유형: Functional / 우선순위: Must
- 설명: sleuth 모드에서 `ZipkinSpanReceiver`가 받은 Zipkin v2 span 중 외부 HTTP 호출 span을 추출한다.
- 수용기준:
  - Given Brave가 export한 Zipkin v2 span 배열(SERVER+CLIENT 혼재), When 추출, Then `kind == "CLIENT"`이고 tag `http.method` **및 비공백 `http.path`**를 가진 span만 선택된다.
  - Given `http.path`가 없거나 공백인 CLIENT span, When 추출, Then 제외된다(path 없는 egress는 발견 불가로 간주).
- 검증 레벨: integration + unit

### REQ-003 — 공통 EgressCall 정규화 규칙
- 유형: Functional / 우선순위: Must
- 설명: 양 소스 span을 단일 `EgressCall(method, path, statusOrNull, traceId, startNanos)`로 환원한다. `statusOrNull`은 **태그 없으면 null**(기본 200 환원은 매핑 단계 REQ-005에서).
- 수용기준:
  - **(path strip)** Given path/URL에 query 포함(`/inventory/stock?type=X`), When 정규화, Then `path == "/inventory/stock"`.
  - **(otel path semconv fallback)** Given OTEL CLIENT span에 `url.path` 없이 `url.full=http://inventory/stock?type=X`(구 semconv), When 정규화, Then `path == "/inventory/stock"`(`url.path`→`http.url`/`url.full` 순 fallback, query strip).
  - **(status)** Given status 태그 있는 span(error 500), When 정규화, Then `statusOrNull == 500`; Given status 태그 없는 성공 span, Then `statusOrNull == null`.
  - **(non-http 제외)** Given http 속성 없는 CLIENT span(예: `rpc.method`만 있는 gRPC), When 정규화, Then egress 목록에 포함되지 않는다.
- 검증 레벨: unit

### REQ-004 — 요청별 trace-id 귀속
- 유형: Functional / 우선순위: Must
- 설명: egress 수집기는 현재 요청 scope의 주입 헤더에서 trace-id를 얻어 그 trace의 egress만 귀속한다.
- 수용기준:
  - Given otel scope, When `TraceKey.forMode("otel").readTraceId(scope.requestHeaders())`, Then traceparent의 주입 traceId가 반환되고 그 traceId의 span만 수집된다.
  - Given sleuth scope, When `TraceKey.forMode("sleuth").readTraceId(scope.requestHeaders())`, Then X-B3-TraceId의 주입 traceId가 반환된다.
  - Given 동시 두 요청(서로 다른 trace), When 각 수집, Then 서로의 egress가 교차 귀속되지 않는다.
- 검증 레벨: integration

### REQ-005 — EgressCall → CapturedHttpCall 환류 (기본값·null-safety·중복제거)
- 유형: Functional / 우선순위: Must
- 설명: 발견된 `EgressCall`을 공개 mapper로 `CapturedHttpCall`(실제 필드명)로 환류한다(§4.7).
- 수용기준:
  - Given `EgressCall(GET, /inventory/stock, null, tid, t)`, When 매핑, Then `CapturedHttpCall.method="GET"`, `urlPath="/inventory/stock"`, **`responseStatus=200`**(기본), `responseBody=""`, `requestBody=null`, `query=빈 Map`, `baggagePropagated=false`, **`responseProvenance=CAPTURED`**.
  - Given `responseBody`가 빈 문자열/null, When 매핑 경로의 `consumedFields` 산출, Then 예외 없이 빈 리스트(공개 mapper/integration 경계에서 검증; `consumedFields` private이므로 직접 호출 아님).
  - **(중복 제거)** Given 한 요청(단일 trace)에서 redirect 캡처와 span 발견이 동일 `(method, urlPath)`를 산출, When 환류, Then `CapturedHttpCall`이 1건만 기록된다(redirect 우선; 요청 단위 dedup, 교차-trace 아님).
  - **(REQ-015 보강)** Given `callSites`가 비어있지 않고 span-발견 호출이 `ExternalCallSite`(responseShape 보유)에 매칭, When `captureHttpCalls` enrichment 경로로 환류, Then `responseProvenance=SYNTHESIZED`·형상-시드 body로 기록된다(REQ-015, `2026-06-24-egress-status-agnostic-stub-requirements.md`). **단 `EgressCallMapper.toCapturedHttpCall` 단위 계약(항상 CAPTURED·빈 body)은 fallback로 유지**되며 `EgressCallMapperTest`는 불변.
- 검증 레벨: unit + integration

### REQ-006 — ZipkinSpanReceiver 수신 인프라
- 유형: Functional / 우선순위: Must
- 설명: Zipkin v2 span을 받는 in-process 수신기. `OtlpTraceReceiver` 패턴 재사용.
- 수용기준:
  - Given Brave가 `POST /api/v2/spans`로 gzip 또는 plain JSON 배열 전송, When 수신, Then 파싱되어 traceId별 버퍼링.
  - Given 32-hex 아닌 traceId span, When 수신, Then 무시(HEX_32 가드).
  - Given analysis 모드 `start()`, Then `endpoint()`=`http://127.0.0.1:<port>`(loopback, 무인증); attach 모드 `start(bindHost, authToken)`, Then `hostEndpoint()`=`http://host.docker.internal:<port>`.
  - Given trace/span 상한, Then `MAX_TRACES=50_000`/`MAX_SPANS_PER_TRACE=10_000`로 evict/cap. **(구현 메모: 이 상수·HEX_32를 공유 상수(예: `TraceReceiverLimits`)로 추출하거나 package-private 공개 — 현재 private이라 재사용 불가.)**
  - **(Boot3 주의)** Boot3 micrometer-brave는 `management.zipkin.tracing.endpoint`에 **full path `/api/v2/spans`까지** 줘야 export됨(Boot2 `SPRING_ZIPKIN_BASEURL`은 base만).
  - **(attach 토큰 — 미해결)** attach 모드 per-run secret(`x-graphrag-token`)은 Zipkin sender의 custom-header 지원에 의존(Sleuth `spring.zipkin.custom-headers` 등). 미지원 sender면 401. → **초기 범위는 analysis-mode(loopback, 무인증)**; attach 토큰 인증은 sender custom-header 가능성 확인 후(별도 REQ-016 후보).
- 검증 레벨: integration

### REQ-007 — egress 수집 await/quiescence (모드별)
- 유형: Non-functional / 우선순위: Must
- 설명: 양 모드의 export 지연을 고려해 egress span을 누락 없이 수집한다.
- 수용기준:
  - **(otel)** Given OTLP 도착, When `OtlpTraceReceiver.isQuiescent`(QUIESCENCE 150ms 재사용) 기준 수집, Then 같은 trace의 CLIENT span 누락 없이 수집.
  - **(sleuth)** Given Brave AsyncReporter flush 지연(~1s, OTEL BSP와 무관), When sleuth용 await 상한(flush 주기 + jitter, 초기값 구현 시 실측·명시) 내 폴링, Then 성공 span도 누락 없이 수집.
  - Given await 상한 초과, Then 침묵 누락이 아니라 경고 로그.
- 검증 레벨: integration

### REQ-008 — 샘플링 강제 (SUT sampler override 불요)
- 유형: Functional / 우선순위: Must
- 설명: 주입 헤더의 sampled 결정만으로 export가 강제되어 SUT 샘플러 설정을 바꾸지 않는다.
- 수용기준:
  - Given SUT sampler probability=0.0, When 주입 `X-B3-Sampled:1`로 요청(sleuth), Then egress CLIENT span이 export·수집된다.
  - Given 빌더의 SUT 기동 env map, When 점검, Then SUT 샘플러 확률 override env가 **없다**(env map 단언; E2E 기동 전 inline assert 또는 `ZipkinSutEnvInjectionTest`).
- 검증 레벨: E2E(REQ-010 시나리오 내) + integration

### REQ-009 — [E2E] otel 모드 redirect-비의존 egress 발견
- 유형: Functional / 우선순위: Must
- 설명: otel 모드 SUT(order-service/InventoryClient)에서 외부 호출이 redirect 없이 발견된다.
- 수용기준:
  - Given order-service를 otel 모드로 기동(`EXTERNAL_INVENTORY_URL`을 WireMock 치환이 아닌 실/호스트 stub으로), When `POST /api/orders`(`type=EXPRESS`로 `InventoryClient.check()`→`GET /inventory/stock?type=...` 유발) 탐색, Then `OtlpTraceReceiver`의 `SPAN_KIND_CLIENT`+http span에서 `(GET, /inventory/stock)`(query-stripped)가 요청 trace에 귀속되고 graph `CapturedHttpCall`에 기록. redirect/`--external-stubs` 미사용. traceparent는 항상 sampled(`-01`)이며 SUT sampler를 override하지 않는다.
- 검증 레벨: E2E black-box

### REQ-010 — [E2E] sleuth/Brave 모드 redirect-비의존 egress 발견
- 유형: Functional / 우선순위: Must
- 설명: sleuth/Brave SUT(legacy-tram/order-web)에서 외부 호출이 redirect 없이 발견된다.
- 수용기준:
  - Given order-web을 빌더 egress 캡처로 기동(zipkin 리포터→ZipkinSpanReceiver), When `POST /orders`(B3 주입) 탐색, Then ZipkinSpanReceiver의 CLIENT span에서 `(POST, /reservations)`가 요청 trace에 귀속되고 graph `CapturedHttpCall`에 기록. redirect 미사용.
- 검증 레벨: E2E black-box

### REQ-011 — 테스트 자원 정리 / 누수 검증 게이트
- 유형: Non-functional / 우선순위: Must
- 설명: docker/SUT를 띄우는 모든 E2E·통합은 모든 종료 경로에서 자기 스코프만 teardown하고 잔존 0을 검증한다(전역 규칙).
- 수용기준:
  - Given E2E가 compose/SUT 기동, When 성공·실패·예외·타임아웃 어느 경로로든 종료, Then 고유 project/label/PID 한정으로 teardown(`docker compose -p <uniq> down -v --remove-orphans` / PID 종료). 신규 E2E는 고유 project를 쓰며, 기존 `e2e/run-legacy-tram-sleuth-e2e.sh`(고정 project + `down -v`)와 별개 하니스 또는 그 스크립트를 본 규칙에 맞게 갱신한다.
  - Given 스위트 종료 후, When `label=com.docker.compose.project=<uniq>`/PID로 잔존 확인, Then 자기 컨테이너·네트워크·볼륨·프로세스 잔존 0(아니면 green/완료 주장 금지).
  - Given 정리, Then `docker system prune`·`docker rm $(docker ps -aq)`·광범위 `pkill -f` 미사용, 공유 인프라 불가침.
- 검증 레벨: process (E2E 하니스 검증)

### REQ-012 — (Won't, 이번 범위) 외부 stub 응답 body 충실도 (4순위)
- 유형: Functional / 우선순위: Won't / 상태: 🔵 out-of-scope (분모 제외)
- 설명: 발견된 호출의 stub 응답 body를 happy-minimal에서 실측 body/에러 계약 기반으로 교체. span은 body 미포함 → redirect/proxy body 캡처 위에 별도 구축.

### REQ-013 — (deferred) sleuth 리포터 런타임 주입성
- 유형: Functional / 우선순위: Should(연기) / 상태: 🔵 deferred (분모 제외)
- 설명: zipkin 리포터 미보유 SUT에 소스 수정 없이 실행 시점 리포터 주입. 초기 범위는 "리포터 존재 시 동작".

### REQ-014 — (deferred) 다운스트림 host 식별 매핑
- 유형: Functional / 우선순위: Should(연기) / 상태: 🔵 deferred (분모 제외)
- 설명: span의 path만으로 모호할 때 SUT config(`*.url`)로 target host 매핑.

### REQ-015 — status-무관 stub register 경로 (활성화됨)
- 유형: Functional / 우선순위: Should / 상태: ✅ in-scope (별도 명세로 이관)
- 설명: redirect 없이 발견된 호출을 stub으로 등록하는 status-무관 경로(현행 404-driven 합성과 별개).
- 이관: 본 REQ는 별도 명세 `2026-06-24-egress-status-agnostic-stub-requirements.md`(REQ-S015-001~008)로
  상세화·구현되었다. 추적은 그 문서의 매트릭스를 따른다.

### REQ-016 — (deferred) attach 모드 Zipkin 토큰 인증
- 유형: Non-functional / 우선순위: Should(연기) / 상태: 🔵 deferred (분모 제외)
- 설명: attach 모드 wildcard 바인드 시 per-run secret 인증. Zipkin sender의 custom-header 지원 확인 후. 초기 범위는 analysis-mode loopback(무인증).

## 추적 매트릭스
| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | otel CLIENT span 추출 | `EgressNormalizerTest` | unit | 🟢 green |
| REQ-002 | sleuth Zipkin CLIENT span 추출(method+path) | `EgressNormalizerTest` + `ZipkinSpanReceiverTest` | unit/integration | 🟢 green |
| REQ-003 | 공통 정규화(strip/semconv/status/non-http) | `EgressNormalizerTest` | unit | 🟢 green |
| REQ-004 | 요청별 trace-id 귀속 | `EgressCollectorTest` + `EgressCollectorWiringTest` | unit/integration | 🟢 green |
| REQ-005 | EgressCall→CapturedHttpCall 환류·null-safety·dedup | `EgressCallMapperTest` + `EgressCollectorWiringTest` | unit/integration | 🟢 green |
| REQ-006 | ZipkinSpanReceiver 인프라 | `ZipkinSpanReceiverTest` + `TraceReceiverLimitsTest` | integration/unit | 🟢 green |
| REQ-007 | egress await/quiescence(모드별) | `EgressCollectorTest#awaitsLate` + `EgressCollectorWiringTest` | unit/integration | 🟢 green |
| REQ-008 | 샘플링 강제(override 불요) | `SleuthEgressDiscoveryE2E#samplerOffStillExports` + `ZipkinSutEnvInjectionTest` | E2E/integration | 🟢 green |
| REQ-009 | [E2E] otel redirect-비의존 발견 | `OtelEgressDiscoveryE2E` | E2E | 🟢 green (sut.jar 조건부) |
| REQ-010 | [E2E] sleuth redirect-비의존 발견 | `SleuthEgressDiscoveryE2E` | E2E | 🟢 green (sut.egress.sleuth=true 조건부) |
| REQ-011 | 테스트 자원 정리/누수 게이트 | `SleuthEgressDiscoveryE2E` (AfterAll try/finally + docker ps 잔존 0 assert) | process | 🟢 green |
| REQ-012 | (4순위) stub body 충실도 | — | — | 🔵 out-of-scope |
| REQ-013 | (연기) 리포터 런타임 주입성 | — | — | 🔵 deferred |
| REQ-014 | (연기) host 식별 매핑 | — | — | 🔵 deferred |
| REQ-015 | status-무관 register (활성화) | 별도 명세 `egress-status-agnostic-stub` REQ-S015-001~008 | — | ✅ 이관 |
| REQ-016 | (연기) attach Zipkin 토큰 인증 | — | — | 🔵 deferred |

Coverage: 11/11 green (100%) — Must 11개: REQ-001~011 전부 🟢. Won't: 1(REQ-012), Deferred: 4(REQ-013/014/015/016) 🔵 분모 제외. (확인일: 2026-06-24)

---
### 리뷰 반영 이력
- 2026-06-24 3벤더 리뷰(Claude Sonnet/Gemini/Cursor) 1회. 반영(전 지적 채택, 코드 검증 후):
  `CapturedHttpCall` 필드명 정정(responseStatus/baggagePropagated/responseProvenance, REQ-005·설계 §4.7),
  EgressCall.statusOrNull=null-when-absent로 일관화(REQ-003·설계 §4.1), `TraceKey.forMode("...")` API表기(REQ-004),
  상수 private→추출/공개 메모(REQ-006), Boot3 full-path·attach 토큰 custom-header 미해결→REQ-016 분리(REQ-006),
  non-http(gRPC) 제외 GWT·otel path semconv fallback(REQ-003), REQ-002 path 존재 요구, redirect+span 중복제거(REQ-005),
  otel quiescence 분기(REQ-007), REQ-008 AC2 env-assert 테스트 매핑, REQ-009 트리거 구체화(POST /api/orders type=EXPRESS),
  REQ-011 기존 스크립트 관계 명시.
