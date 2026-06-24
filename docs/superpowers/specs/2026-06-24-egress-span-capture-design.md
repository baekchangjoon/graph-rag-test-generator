# 설계: 트레이싱 기반 외부 HTTP egress 발견 (리다이렉트 비의존) — otel + sleuth/Brave

- 작성일: 2026-06-24
- 브랜치: `feat-egress-span-capture`
- 상태: 설계(브레인스토밍 산출) — 3벤더 리뷰 1회 반영됨, 사용자 검토 대기
- 선행 PoC: `poc/egress-span-capture/FINDINGS.md` (전략 S-c VALIDATED)

## 1. 목표와 시나리오

이 도구(graph-rag-test-generator)는 SUT 엔드포인트를 탐색해 **실행 가능한 격리 테스트를
자동 생성**한다. 엔드포인트가 외부 HTTP 서비스를 호출하면, 생성 테스트가 돌려면 그 외부 의존을
**stub(mock)으로 격리**해야 하고, stub을 깔려면 먼저 **"이 엔드포인트가 어떤 외부 호출
(method+path)을 하는가"를 발견**해야 한다.

> **목표 = 외부 호출 "발견"을, SUT의 outbound base URL을 임베디드 WireMock으로 리다이렉트하지
> 않고도 해내는 것.**

### 해결하려는 시나리오 (= "0건" 문제)
현재 발견 경로 두 가지가 현실 SUT에서 모두 막힌다.
- **정적**(`ResponseDtoIndexer`): RestTemplate 5메서드 + 리터럴 URL + `X.class`만 인식 → 좁음.
- **런타임**(`HttpCaptureServer`): SUT가 우리 WireMock을 바라보도록 base URL 리다이렉트
  (`--external-stubs`/`--sut-env`)가 돼야만 캡처.

리다이렉트가 불가능한 SUT(독자 HTTP 클라이언트, config placeholder URL, 비-HTTP 보안 에이전트,
플래그 미지정)에서는 **발견 0건 → 외부 의존 엔드포인트의 테스트를 생성하지 못함**. 이 작업은
**빌더가 이미 붙이는 트레이싱(OTEL javaagent / Spring Cloud Sleuth·Brave)의 egress CLIENT span을
우리 수신기로 받아** 발견한다. 대표 시나리오는 레거시 sleuth/Brave MSA(eventuate-tram 예제의
order-web→reservation 동기 호출).

## 2. 범위

### In scope
- **두 트레이스 모드 모두 1급 지원(필수):** otel(OTLP CLIENT span) + sleuth/Brave(Zipkin v2 CLIENT span).
- 요청별 trace-id 귀속으로 **(method, path[, status]) egress 호출-사이트 발견**.
- 발견 결과를 **graph의 `CapturedHttpCall` 레코드로 환류**(§4.7). testlib `HttpMockClient.stub(method,
  urlPath)`와 키 정합(method+path).
- **두 모드 각각 out-of-process E2E로 검증(필수).**

### 산출물 경계 — "발견 레코드"이지 "404-driven stub 합성"이 아니다
현행 stub 합성(`synthesizeStubsForUnmatched`, runner L1497)은 **WireMock이 unmatched로 돌려준
404 시그널**에 의존한다(즉 리다이렉트 전제). span 기반 발견에는 404 시그널이 없다. 따라서 이
작업의 1차 산출물은 **런타임 발견된 `CapturedHttpCall` 목록(graph 기록)**이며, 기존 404-driven
합성 경로를 대체하지 않는다. redirect 없이 발견된 호출을 stub으로 **등록**까지 하려면 status-무관
register 경로가 필요하고, 이는 별도 요구사항(§8)으로 분리한다.

> **갱신(REQ-015 구현됨):** status-무관 register 경로는
> [docs/superpowers/specs/2026-06-24-egress-status-agnostic-stub-design.md]로 구현되었다. 이제
> span-발견 호출은 `ExternalCallSite` 매칭 시 형상-시드 body로 `CapturedHttpCall`(SYNTHESIZED)에
> 기록되어 생성 테스트 stub에 등록된다(빈 body가 더 이상 1차 산출물이 아니다). 실측 body 충실도만
> 여전히 별도(4순위).

### Out of scope — 4순위(외부 stub 합성 "입력 품질")
> **4순위 정의:** 발견된 외부 호출에 붙는 stub의 **응답 body 충실도**를, 현재의 happy-path
> minimal JSON(형상 기반 최소 유효 body)에서 **실제로 캡처한 응답 body / 에러 계약(error
> envelope)** 기반으로 교체하는 작업.

분리 이유: 실제 body를 얻으려면 outbound가 body를 보는 recorder에 도달해야 함 = WireMock-리다이렉트
(B2) 캡처 또는 body-캡처 프록시. **span 기반(이 작업)은 body를 실어 나르지 않는다.** 4순위는 span이
아니라 redirect/proxy body 캡처에 의존하며, 이 작업의 **발견** 위에 별도로 구축되어 합류해야
완성된다. 본 작업이 산출하는 `CapturedHttpCall`의 body 필드는 빈 값(§4.7)을 유지한다.

## 3. 현재 구조와 재사용 자산

| 자산 | 역할 | 재사용 방식 |
|---|---|---|
| `OtlpTraceReceiver` | in-process OTLP 수신, 모든 span을 trace-id로 per-trace 버퍼(`SpanRecord{kind, attrs,…}`) | otel egress: 도착 중인 CLIENT+http span을 **필터만** 추가 |
| `SqlCaptureBackend.Scope` | `requestHeaders()`(traceparent/B3 주입) + `drain()` | egress 수집을 같은 요청 scope에 **병렬 추가** |
| `TraceKey`(`OtelTraceKey`/`SleuthTraceKey`) | `readTraceId(outboundHeaders)` — 주입 헤더에서 trace-id 추출 | egress 수집기의 **traceId 취득 통일 API** |
| `TraceParent`/`B3TraceId` | 결정적 32-hex traceId 발급(W3C / B3 멀티헤더) | 그대로 사용(주입 헤더가 샘플링 결정 운반) |
| `OtlpTraceReceiver`의 HEX_32·MAX_TRACES·MAX_SPANS_PER_TRACE·evict·`endpoint()`/`hostEndpoint()`/per-run secret | per-trace 상태·노출 패턴 | sleuth용 `ZipkinSpanReceiver`를 **동일 패턴으로 신설** |
| `captureHttpCalls`→`CapturedHttpCall` | RawHttpExchange를 graph 레코드로 | egress 발견을 **동일 레코드로 환류**(§4.7) |
| testlib `HttpMockClient.stub(method, urlPath)` | 외부 stub은 method+path로 키링 | 발견 산출과 1:1 정합 |

## 4. 설계

### 4.1 공통 정규화 — `EgressCall`
양 소스(SpanRecord / Zipkin v2 span)를 단일 레코드로 환원한다.
```
EgressCall(method, path, statusOrNull, traceId, startNanos)
```
정규화 규칙(단위 테스트 대상):
- **kind 필터(모드별 문자열 상이 — 검증됨):** otel `SpanRecord.kind == "SPAN_KIND_CLIENT"`
  (OTLP proto enum `getKind().name()`; OtlpTraceReceiver L186) / zipkin `kind == "CLIENT"`.
  동일 문자열로 필터하면 otel에서 0건이 되므로 모드별로 분기한다.
- **positive http 판별(false positive 방지):** CLIENT span 중 `http.request.method`(신 semconv)
  또는 `http.method`(구/zipkin) **속성이 존재**하는 것만 egress로 본다(gRPC 등 비-HTTP CLIENT 제외).
- **method:** OTEL `http.request.method`/`http.method` ↔ Zipkin tag `http.method`.
- **path:** **query string 제거(path-only).** OTEL `url.path`(신) 또는 `http.url`/`url.full`에서
  `?` 앞부분 ↔ Zipkin tag `http.path`(이미 path-only). 근거: WireMock `urlPathEqualTo`가 path-only
  매칭이고, 기존 `HttpCaptureServer.drainNewExchanges`도 `getUrl().split("?")[0]`로 strip(L109).
- **status:** OTEL `http.response.status_code`(신)/`http.status_code`(구) ↔ Zipkin tag
  `http.status_code`. **Brave 기본은 성공 status를 생략** → 태그가 있으면 그 정수, **없으면
  `null`**. (기본 200 환원은 `EgressCall` 계층이 아니라 매핑 단계(§4.7)에서 — `EgressCall`은 span의
  충실한 표현으로 두고, primitive int인 `CapturedHttpCall.responseStatus`에 구체값을 매핑 시 부여.)

### 4.2 otel 모드
`OtlpTraceReceiver`는 이미 한 trace의 **모든** span을 보관한다. egress 수집기는
`receiver.spans(traceId)`에서 §4.1 필터(`SPAN_KIND_CLIENT` + http 속성)를 적용해 `EgressCall`로
환원한다. 신규 수신기 불요(DB span 필터(`OtelSpanCapture`)와 평행).
- **HTTP client 계측 전제:** OTEL javaagent는 HTTP client instrumentation이 **기본 on**이라
  `OtelAgent.otlpEnv`에 추가 env 없이 outbound CLIENT span이 OTLP로 나온다. E2E-2가 이를 검증하며,
  만약 비활성 환경이면 `OTEL_INSTRUMENTATION_*` 토글을 추가한다(구현 시 확인).

### 4.3 sleuth/Brave 모드 — `ZipkinSpanReceiver`(신규)
`OtlpTraceReceiver`와 평행한 in-process 수신기를 신설한다.
- **엔드포인트:** `POST /api/v2/spans` 수신. Content-Encoding gzip / plain JSON 모두 처리, Zipkin v2
  JSON 배열 파싱.
- **상태/용량:** 32-hex traceId 키 per-trace 버퍼 + evict. **`MAX_TRACES`/`MAX_SPANS_PER_TRACE`는
  `OtlpTraceReceiver` 상수를 재사용**(메모리 폭주 방지). 비정상 traceId 무시(HEX_32).
- **bind/endpoint 이중성(필수 — attach 모드 침묵 실패 방지):** `OtlpTraceReceiver`와 동일하게
  (a) analysis 모드 `start()` → loopback 바인드 + `endpoint()`=`http://127.0.0.1:<port>`,
  (b) attach 모드 `start(bindHost, authToken)` → `0.0.0.0` 바인드 + `hostEndpoint()`=
  `http://host.docker.internal:<port>` + per-run secret(`x-graphrag-token` 동급) 검증.
- **SUT env 배선(otel의 `OtlpTraceReceiver` 주입과 대칭 — `BuilderCli`/`AnalysisEnvironment`에 추가):**

  | 키 | analysis 모드 | attach 모드 |
  |---|---|---|
  | `SPRING_ZIPKIN_BASEURL` | `endpoint()` | `hostEndpoint()` |
  | `SPRING_ZIPKIN_SENDER_TYPE` | `web` | `web` |
  | `extra_hosts` (compose) | — | `host.docker.internal:host-gateway` |
  | (Boot3 micrometer-brave) | `management.zipkin.tracing.endpoint` | 동일, host 치환 |

  - **Boot3 주의:** `management.zipkin.tracing.endpoint`는 **full path `/api/v2/spans`까지** 줘야
    export됨(Boot2 `SPRING_ZIPKIN_BASEURL`은 base만 받고 클라이언트가 path를 붙임).
  - **상수 재사용:** `OtlpTraceReceiver`의 `MAX_TRACES`/`MAX_SPANS_PER_TRACE`/`HEX_32`는 현재
    `private`이라 직접 재사용 불가 → 공유 상수(예: `TraceReceiverLimits`)로 추출하거나 package-private로
    공개한다(구현 task).
  - **attach 토큰(미해결):** attach wildcard 바인드 시 per-run secret(`x-graphrag-token`)은 Zipkin
    sender의 custom-header 지원에 의존(Sleuth `spring.zipkin.custom-headers` 등). 미지원 sender면 401.
    → **초기 범위는 analysis-mode loopback(무인증)**, attach 토큰 인증은 별도 REQ(요구사항명세 REQ-016).

### 4.4 탐색 루프 연동
요청 1건 scope에서: `requestHeaders()`로 trace-id(+B3 Sampled=1) 주입 → 요청 발사 →
**egress 수집(SQL `drain()` 이전)** → §4.7로 `CapturedHttpCall` 환류.
- **수집 순서(중요 — otel):** `OtelSpanCapture.OtelScope.drain()`은 finally에서
  `receiver.remove(traceId)`로 그 trace 버퍼를 비운다. 따라서 egress는 **`sqlScope.drain()` 호출
  이전**(span이 아직 receiver에 있을 때)에 수집해야 한다. sleuth는 SQL=로그파싱이라 ZipkinSpanReceiver
  버퍼가 SQL drain에 영향받지 않으나, 일관성을 위해 동일 순서로 둔다(수집 후 `zipkinReceiver.remove(traceId)`).
- **traceId 취득:** `httpCapture.traceKey().readTraceId(scope.requestHeaders())`로 통일(모드 인지:
  `OtelTraceKey`=traceparent, `SleuthTraceKey`=X-B3-TraceId). 기존 `traceparentTraceId(...)`(otel 전용)
  대신 이 API로 — sleuth 귀속이 깨지지 않도록.
- **수집 지점:** `doSendWithScope`의 `http.send()` 직후, `sqlScope.drain()` 이전. 수집한 egress는
  `InvocationOutcome`→`PathCandidate`로 전달되어 `captureHttpCalls(candidate)`가 환류한다.
- **중복 제거:** redirect 캡처와 span 발견은 공존 가능. 한 요청(동일 traceId 범위)의 redirect
  exchange와 egress를 `(method, urlPath)`로 dedup(redirect 우선). `CapturedHttpCall`에는 traceId
  필드가 없으므로 dedup은 요청 단위로만 적용한다(교차-trace dedup 아님).
- **analysis 모드 sleuth(중요):** 현재 analysis 경로는 traceMode와 무관하게 OTEL javaagent를 SUT에
  부착한다. sleuth 모드에서는 attach 경로와 동일하게 **OTEL javaagent를 제외**해야 한다(Brave/OTEL 이중
  계측·`brave.Tracing` 빈 충돌 회피).
- **await/quiescence(모드별 상이 — SQL 상수 그대로 못 씀):** **Brave AsyncReporter는 기본 flush
  주기가 ~1s**로 OTEL BSP(100ms)·OtelSpanCapture QUIESCENCE(150ms)와 무관하다. SQL drain 상수를
  그대로 쓰면 늦은 span을 침묵 누락한다. → egress용 별도 상수: otel은 OTLP 도착 기준(기존
  isQuiescent 재사용), **sleuth는 AsyncReporter flush 주기 + jitter(시작값 ~1.2–2.0s)**를 await
  상한으로 둔다. 정확값은 구현 시 PoC 환경에서 실측해 확정(FINDINGS 보강).

### 4.7 EgressCall → CapturedHttpCall 매핑 (통합 seam)
`captureHttpCalls`가 만드는 실제 record `CapturedHttpCall(id, pathId, method, urlPath, query,
requestBody, **responseStatus**(int), responseBody, consumedFields, **baggagePropagated**(boolean),
**responseProvenance**(Provenance{CAPTURED,SYNTHESIZED}))`로 환원한다.

| CapturedHttpCall 필드 | EgressCall 출처 / 기본값 |
|---|---|
| `method` | `EgressCall.method` |
| `urlPath` | `EgressCall.path` (query-stripped) |
| `query` | 빈 `Map`(span은 분해된 query를 주지 않음) |
| `requestBody` | `null`(span에 없음 — 기존 blank→null 규약과 일치) |
| `responseStatus` | `EgressCall.statusOrNull` ?? **200** |
| `responseBody` | **빈 문자열 `""`**(`null` 아님 — null-safety) |
| `consumedFields` | blank/null body → **빈 리스트**(아래 null-safety) |
| `baggagePropagated` | `false`(span 경로엔 baggage 매칭 없음) |
| `responseProvenance` | `CAPTURED`(실 외부 호출; 합성 stub site 매칭 시 SYNTHESIZED) |

**중복 제거:** 한 요청(동일 traceId 범위)에서 redirect 캡처(`drainNewExchanges`)와 span 발견이 동일
`(method, urlPath)`를 산출하면 `CapturedHttpCall`을 1건만 기록한다(redirect 우선). dedup은 요청 단위로만
적용 — `CapturedHttpCall`에 traceId 필드가 없고 collect는 이미 단일 trace 결과이므로 `(method, urlPath)`로
충분(교차-trace dedup 아님).

**null-safety(검증된 위험):** `consumedFields(responseBody)`는 `Json.mapper().readTree(responseBody)
.fieldNames()`를 호출하므로(runner L1920) `null`/`""`에서 깨질 수 있다. egress 경로는 `responseBody=""`
를 쓰고, `consumedFields`는 blank/`null` 입력에 **빈 리스트**를 반환하도록 가드한다(소형 수정).

### 4.5 샘플링과 SUT 설정
주입 헤더의 **B3 Sampled=1 / traceparent sampled flag**가 샘플링 결정을 운반. PoC에서 **SUT
sampler=0.0이어도 주입만으로 export 강제됨**을 확인 → **SUT 샘플러 override 불요.**

### 4.6 적용 전제(리포터)와 주입성 — 미해결, REQ로 분리
sleuth 경로는 SUT가 **Brave/Zipkin 리포터를 클래스패스에 갖고** 우리 수신기를 향하게 할 수 있어야
한다. PoC 샘플은 기본 상태에 리포터가 없어 **`spring-cloud-sleuth-zipkin`을 추가**했다(아래 픽스처
주). 실 SUT가 리포터 미보유 시 처리(실행 시점 classpath/loader.path 주입 vs "리포터 존재" 전제)는
별도 요구사항(§8). 초기 범위는 "리포터 존재 시 동작"으로 한정한다.

> **픽스처 주:** `samples/legacy-tram/order-web/build.gradle.kts`의 `spring-cloud-sleuth-zipkin`은
> E2E-1 테스트 픽스처로 **의도적으로 유지**한다(우리 샘플=테스트 자산). 이는 §4.6의 실 SUT 적용성
> 우려와 별개이며, 런타임 주입 방식은 위 REQ로 별도 추적한다.

## 5. PoC 결과 요약 (전략 S-c VALIDATED)
legacy-tram order-web(Boot 2.7.18 / Sleuth 3.1.9·Brave / Java 8) + zipkin 리포터, base-url→호스트
수신기, 트림 부팅(mysql+order-web).
- ✅ Brave가 egress CLIENT span을 Zipkin v2로 export, 주입 B3 traceId(128-bit) 보유, HEX_32 키 정합.
- ✅ SUT sampler=0.0 + 주입 Sampled=1 → export 강제(샘플러 override 불요).
- 메타데이터: `http.method`/`http.path` 항상, `http.status_code`는 **에러에만**, body/remote host 없음.
- 캡처 형상: `{"traceId":"<32hex>","kind":"CLIENT","tags":{"http.method":"POST","http.path":"/reservations"[,"error":"500","http.status_code":"500"]}}`

## 6. 한계 / caveat (정직)
1. **status 비대칭**(sleuth): Brave 기본은 성공 status 생략 → 성공은 기본 200으로 둠(§4.1/4.7).
2. **다운스트림 host**: Zipkin CLIENT span의 `remoteEndpoint` null → path만. host 식별은 SUT
   config(`*.url`) 매핑으로 보완(별도 REQ §8). 발견엔 method+path로 충분.
3. **표준 클라이언트만**(otel·sleuth 공통): 표준 HTTP 클라이언트 경유만 span 생성. 독자
   프레임워크/비-HTTP 소켓은 1순위로 못 잡음 → 2순위(정적 recognizer) 영역.
4. **body 없음**: 4순위 영역(§2).

## 7. E2E / 수용 테스트 (완료 정의 = 양 모드 E2E green)

> 두 모드는 각각 **out-of-process E2E**로 검증한다(둘 다 필수, 둘 다 green). **redirect/WireMock
> 치환을 쓰지 않고** 발견됨을 검증하는 것이 핵심.

- **E2E-1 (sleuth/Brave) [필수]:** legacy-tram `order-web`을 빌더 egress 캡처로 기동 →
  `POST /orders`(B3 주입) → assert: ① `ZipkinSpanReceiver.spans(traceId)`에 CLIENT span 존재,
  ② 환류된 graph `CapturedHttpCall`에 `(POST, /reservations)`가 요청 trace에 귀속. redirect 미사용.
- **E2E-2 (otel) [필수]:** order-service(InventoryClient)를 otel 모드로 기동 → 재고 조회 유발
  엔드포인트 → assert: ① `OtlpTraceReceiver`에서 `SPAN_KIND_CLIENT`+http span 필터,
  ② graph `CapturedHttpCall`에 `(GET, /inventory/stock)`(query-stripped) 귀속. `EXTERNAL_INVENTORY_URL`은
  WireMock 치환이 아니라 실 stub(또는 호스트 스텁)로 두어 redirect-비의존을 보장.
- **Unit:**
  - 정규화기: OTEL SpanRecord(`SPAN_KIND_CLIENT`, 신/구 semconv 키) / Zipkin v2 span → `EgressCall`;
    status 추론(에러값 vs 기본 200), path query-strip, 비-http CLIENT(gRPC) 제외.
  - `ZipkinSpanReceiver`: gzip/plain 파싱, kind 필터, HEX_32 키 거부, per-trace 버퍼/evict, bind/endpoint.
  - `EgressCall`→`CapturedHttpCall` 매핑(기본값/ null-safety), `consumedFields` blank-safe.

### 7.1 테스트 자원 정리 / 누수 검증 게이트 (전역 규칙 — 모든 E2E·통합 적용)
E2E-1/E2E-2는 docker compose·SUT 프로세스를 띄우므로 다음을 acceptance의 일부로 강제한다.
- **모든 종료 경로에서 teardown 보장:** 성공·실패·예외·타임아웃·시그널 모두. JUnit `@AfterAll`
  (Testcontainers/Ryuk) 또는 compose 기동 시 `try/finally`로 `docker compose -p <uniq> down -v
  --remove-orphans`. 백그라운드 프로세스는 PID 캡처 후 그 PID만 종료.
- **자기 스코프만 정리(무차별 금지):** 고유 project name/label로 자기 것만. `docker system prune`·
  `docker rm $(docker ps -aq)`·광범위 `pkill -f` 금지. 공유·장수명 인프라 불가침.
- **누수 검증 게이트:** 스위트 종료 후 자기 컨테이너/프로세스/네트워크/볼륨 잔존 0을 확인
  (`label=com.docker.compose.project=<uniq>` 필터). 잔존 시 green/완료 주장 금지(PR 전 green
  게이트의 일부). PoC 하니스가 이 규약을 예시한다(`-p egress-poc` + `down -v` + PID 한정 종료 +
  잔존 0 검증).

## 8. 미해결 / 후속 (REQ 후보)
- **R-주입성:** sleuth 리포터 미보유 SUT의 런타임 리포터 주입(classpath/loader.path) vs 전제조건.
- **R-host매핑:** 다운스트림 host 식별 — SUT config(`*.url`) → target 매핑 규칙.
- **R-status무관 register:** redirect 없이 발견된 호출을 stub으로 등록하는 status-무관 경로(현행
  404-driven 합성과 별개).
- **(4순위, 별도 작업)** 실측 body / 에러 계약 기반 stub 충실도 — redirect/proxy body 캡처 위에 구축.

## 9. 컴포넌트 경계 요약
- `ZipkinSpanReceiver`(신규): Zipkin v2 수신·파싱·per-trace 버퍼·bind/endpoint 이중성. 입력=HTTP POST,
  출력=`spans(traceId)`.
- `EgressCall` + 정규화기(신규): SpanRecord/Zipkin span → `EgressCall`. 순수 함수, 단위 테스트 용이.
- egress 수집기(신규): scope의 traceId(`TraceKey.readTraceId`)로 양 수신기에서 `EgressCall` 모음 →
  `CapturedHttpCall` 환류(§4.7).
- 기존 변경 최소: `OtlpTraceReceiver` 읽기 재사용(필터만), 주입은 기존 `requestHeaders()` 경로,
  `consumedFields` blank-safe 가드(소형).

---
### 리뷰 반영 이력
- 2026-06-24 3벤더 design 리뷰(Claude Sonnet / Gemini 3.5 Flash / Cursor) 1회. 반영: otel kind
  문자열 `SPAN_KIND_CLIENT`(§4.1), 통합 seam·매핑표·null-safety(§4.7), Brave AsyncReporter 별도
  quiescence(§4.4), 수신기 bind/endpoint 이중성·env 배선(§4.3), traceId 취득 `TraceKey` API(§4.4),
  path query-strip·status 기본 200(§4.1), 404-driven 합성과의 경계·status무관 register REQ 분리
  (§2/§8), positive http 판별(§4.1), 픽스처 주(§4.6), E2E assert 대상 명시(§7). 거부: 없음(전
  지적 채택, 일부는 altitude상 "상수 실측은 구현 시"로 위임).
- 2026-06-24 요구사항명세 3벤더 리뷰의 코드-검증 지적을 design에 역전파: `CapturedHttpCall` 실제
  필드명(`responseStatus`/`baggagePropagated`/`responseProvenance`, §4.7), `EgressCall.statusOrNull`=
  null-when-absent로 일관화(§4.1, 200은 매핑 단계), 상수 private→추출/공개(§4.3), Boot3 full-path·
  attach 토큰 custom-header 미해결→REQ-016 분리(§4.3), span↔redirect 중복 제거 규칙 명시(§4.7).
