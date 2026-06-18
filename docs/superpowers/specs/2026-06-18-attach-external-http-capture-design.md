# attach 모드 외부 HTTP 캡처 배선 — 설계 (v2)

- 일자: 2026-06-18
- 브랜치: feat-attach-external-http
- 리뷰: 3-모델(Sonnet/GPT; Gemini timeout→생략) needs-condition 반영(v2). 핵심 변경: 보안 per-run 토큰(경로-접두사 방식) 채택.

## 문제

attach 모드는 SUT의 외부 HTTP(downstream) 호출을 캡처하지 못한다:
1. `AttachedComposeEnvironment.httpCapture()`가 `null`을 반환(`attach v1: 외부 HTTP 캡처 미지원`).
2. attach는 호스트 `HttpCaptureServer`(임베디드 WireMock)를 띄우지 않는다.
3. `EndpointExplorationRunner`는 `httpCapture == null ? List.of() : httpCapture.drainNewExchanges()`로 캡처를 스킵.
4. `BuilderCli.runAttached`는 `--external-stubs`/`--sut-env`(`{{wiremock}}` 치환)를 배선하지 않는다.

docs/26 "v1 한계 #3"의 사유(컨테이너→호스트 미도달)는 OTEL SQL 캡처가 도입한 `host.docker.internal:host-gateway`로 이미 해소됨. 남은 것은 "미배선".

## 범위

- **포함**: attach에서 SUT 외부 HTTP 호출을 호스트 임베디드 WireMock으로 받아 `CapturedHttpCall` 캡처. `--sql-capture` log/otel 무관 **항상** 동작.
- **제외(비목표)**: 컨테이너측 WireMock 서비스 방식. OTEL http-client span 기반 외부 HTTP 캡처(span에 body 없음). 다중 외부 서비스의 개별 토큰(단일 per-run 토큰 공용).

## 보안 (per-run 토큰 — 경로-접두사 방식)

호스트 WireMock은 컨테이너 도달을 위해 **모든 인터페이스(0.0.0.0)** 에 bind된다(WireMock 3.13.0 기본; loopback 한정 아님 — 업그레이드 시 재확인). 이는 `docs/decisions/mock-services-security-model.md`의 "compose 밖 미노출" 전제를 벗어나므로, 동류인 OTLP 리시버(attach 0.0.0.0+per-run secret)와 **일관되게 per-run 토큰**으로 보호한다.

OTLP는 agent가 `OTEL_EXPORTER_OTLP_HEADERS`로 토큰 헤더를 붙이지만, 외부 HTTP는 **SUT 앱 코드가 호출**하므로 outbound 헤더 주입이 불가하다. 대신 **빌더가 주입하는 base URL에 토큰을 경로 접두사로 심는다**(SUT-투명):

- `{{wiremock}}` → `http://host.docker.internal:<port>/<token>` (token = per-run 256-bit hex; `newOtlpSecret`과 동일 생성기 공용화).
- SUT는 `<base>/inventory/stock`을 호출 → 실제 경로 `/<token>/inventory/stock`.
- WireMock `RequestFilterV2` 확장이: 경로가 `/<token>/`로 시작하지 않으면 **401 stop**; 시작하면 접두사를 제거한 요청으로 `continueWith`(RequestWrapper로 URL rewrite) → 사용자 stub는 **토큰 무관**하게 매칭.

→ LAN의 타 프로세스가 토큰 없이 호스트 WireMock을 쳐도 401(가짜 serve event 주입·stub 조회 차단).

**토큰은 캡처 데이터에서 반드시 불가시(가장 중요)**: 토큰은 *빌더 캡처 시점의 접근 제어*일 뿐, 생성 테스트로 흘러가면 안 된다. `CapturedHttpCall.urlPath`가 `/<token>/inventory/stock`이면 test-generator가 `scope.http().stub(GET, "/<token>/inventory/stock")`을 생성하고, 테스트 실행 환경의 SUT는 토큰 없는 `/inventory/stock`을 호출해 **stub 불일치로 외부 목이 깨진다**. 따라서 `HttpCaptureServer.drainNewExchanges`가 기록하는 `urlPath`는 반드시 토큰 접두사가 **제거된 깨끗한 경로**여야 한다. WireMock serve event는 보통 *원본(pre-filter)* URL을 보존하므로, RequestFilter의 rewrite에만 의존하지 말고 **드레인 코드가 알고 있는 token으로 접두사를 직접 strip**한다(serve-event 동작과 무관하게 견고). query/응답/consumedFields는 토큰 영향 없음.

## 종단 데이터 흐름 (생성 테스트에서의 활용)

이 기능은 새 파이프라인을 만들지 않고, attach가 **기존 Phase 2 외부-HTTP 캡처→목 파이프라인**에 데이터를 공급하게만 한다(현재는 attach가 `httpCapture()=null`이라 공급이 끊겨 있음). 흐름:

1. **빌더(attach)** — SUT 외부 호출 → 호스트 WireMock(토큰 검증·strip) → `HttpCaptureServer.drainNewExchanges` → `EndpointExplorationRunner.captureHttpCalls` → graph.json의 **`CapturedHttpCall`**(깨끗한 urlPath, query, responseStatus, responseBody, consumedFields, baggagePropagated).
2. **test-generator** — `HttpMockComposer.compose(httpCallsForPath)`가 호출당 `scope.http().stub(method, urlPath).withQueryParam(...).respondJson(status, consumedFields-투영 body).register();`를 생성(`mocksBlock`). (기존 코드 — 변경 없음.)
3. **생성 테스트 실행(테스트 환경, docs/06)** — testlib `WireMockHttpMockClient`가 그 stub을 테스트-환경 WireMock에 등록 → 생성 테스트가 SUT를 칠 때 SUT의 외부 호출이 기록된 응답을 받는다(baggage 매칭으로 병렬 격리). SUT의 외부 URL은 테스트 환경이 자체 mock으로 redirect(빌더의 per-run 토큰과 무관).

즉 **빌더의 토큰/host-gateway 배선은 캡처 단계 한정**이고, 캡처된 `CapturedHttpCall`은 토큰이 제거된 깨끗한 데이터라 analysis 모드 캡처와 동일하게 test-generator·생성 테스트에서 그대로 활용된다. attach 캡처는 analysis 캡처와 **동일 스키마·동일 소비 경로**를 탄다.

## 설계 (접근 A — OTLP 리시버 배선 미러링)

### (A) HttpCaptureServer — host 도달 + 토큰 필터

`io.graphrag.builder.env.HttpCaptureServer`:
- 생성/start에 nullable `authToken` 수용. token != null이면 WireMock `extensions(...)`에 위 `RequestFilterV2`(토큰 접두사 검증·strip) 등록. token == null이면 기존 동작(analysis 무토큰 loopback).
- `int port()`, `String hostBaseUrl()`(= `http://host.docker.internal:<port>` + token이면 `/<token>` 접두사 포함). `baseUrl()`(loopback, analysis용)은 유지.
- WireMock 기본 0.0.0.0 bind 확인(필요 시 `bindAddress`로 명시).

### (B) AttachedComposeEnvironment — OtlpReceiver + HttpCaptureServer 둘 다 소유

생성자 계약을 명확히(OTEL attach 회귀 방지):
- `(Config, DbConfig.Type)` → `(config, dbType, null, null)` 위임(기존 호환).
- 신규 `(Config, DbConfig.Type, OtlpTraceReceiver, HttpCaptureServer)` — 두 nullable 자원 보유. (기존 3-arg `(Config, DbType, OtlpTraceReceiver)`는 `(…, otlp, null)` 위임으로 유지하거나 4-arg로 통합 — 호출부는 runAttached 1곳.)
- `httpCapture()`가 주입된 HttpCaptureServer 반환(현 null). `close()`에서 둘 다 stop(이미 otlp stop 있음 → http도 추가).

### (C) BuilderCli.runAttached — 배선 (OtlpReceiver 패턴 + try/finally 정리)

순서(서버를 override YAML 생성 *전* 시작해 포트 확정):
1. `warnIfHostGatewayUnsupported()`를 **otel 블록 밖**으로 이동(외부 HTTP에도 host-gateway 필요하므로 attach에서 항상 1회).
2. 호스트 `HttpCaptureServer`를 per-run token + `config.externalStubsDir()`로 시작.
3. `config.sutEnv()`의 `AnalysisEnvironment.WIREMOCK_PLACEHOLDER`(`{{wiremock}}`)를 `httpCapture.hostBaseUrl()`(토큰 접두사 포함)로 치환한 env를 OTEL env(otel 모드 시)와 합쳐 `OverrideComposeGenerator.Spec.extraEnv`로 주입.
4. `addHostGateway=true`를 **attach에서 항상**(현 otel 전용 → 분리).
5. **실패 정리**: HttpCaptureServer(및 OtlpTraceReceiver)를 시작한 순간부터 `AttachedComposeEnvironment` 생성 성공 전까지의 예외(stub 로드 실패·override 생성/쓰기 실패)에서 `try/finally` 또는 명시적 close로 서버를 stop(고아 서버 방지). 생성 성공 후엔 env가 close 책임.

`OverrideComposeGenerator`는 이미 `extraEnv` 병합·`addHostGateway` 지원 → 추가 변경 최소(호출부의 addHostGateway 인자만 항상 true).

### (D) E2E 수용 테스트

order-service의 EXPRESS 주문 경로는 `EXTERNAL_INVENTORY_URL`로 inventory를 호출(`InventoryClient.check`). 전제:
- userId는 FK(orders.user_id→users.id)라 `SampleInputSynthesizer`가 부모 users 행을 probe로 seed → `findById(probe-userId)` 성공(별도 user seed 불필요). type="EXPRESS"는 `StaticLiteralOracle`이 SUT 리터럴에서 추출 → 탐색이 EXPRESS arm 도달(analysis e2e가 이미 EXPRESS 201/409 검증하므로 동일 탐색으로 도달).

수용 기준(신규 `e2e/run-attach-ext-http-e2e.sh` — 기존 attach 스크립트와 **포트·project 충돌 회피**: PROJECT=`grb-attach-order-exthttp`, app-port 58081, jacoco-port 16301):
1. attach 분석 정상 완료 + teardown clean.
2. graph.json에 inventory **`CapturedHttpCall`** ≥1건(URL/메서드 일치; 응답 body·SUT 읽은 필드 보존). `--external-stubs e2e/external-stubs --sut-env EXTERNAL_INVENTORY_URL={{wiremock}}` 사용.
3. host-gateway로 컨테이너→호스트 WireMock 도달 확인(캡처 0이면 실패). 토큰 불일치 요청은 401(필터 동작 — 단위로 검증).
4. 기존 attach 회귀(`run-attach-e2e.sh`, `run-attach-otel-e2e.sh`) green 유지.

## 영향 범위 / 위험

- `HttpCaptureServer`: token-aware start + `port`/`hostBaseUrl` + RequestFilter 확장. token=null 경로(analysis)는 무변경.
- `AttachedComposeEnvironment`: 생성자에 HttpCaptureServer nullable 추가(4-arg), close에 stop 추가. 기존 2/3-arg 위임 유지.
- `runAttached`: 외부 stub/sutEnv 배선 → **log 모드 attach도 host-gateway 주입**(의도). `--external-stubs` 미제공 시 WireMock은 stub 없이 떠 downstream에 404(기존 graceful) — 의도된 동작(운영자가 stub 제공).
- **Docker<20.10**: host-gateway 미지원 → 컨테이너가 호스트 WireMock 미도달 → 외부 캡처 0. `warnIfHostGatewayUnsupported()`로 경고(하드 실패 아님 — 외부 캡처 불필요한 attach까지 막지 않기 위해). docs/26에 "외부 HTTP 캡처는 Docker 20.10+ 필요" 한계 명시.
- `ExplorationEnvironment.httpCapture()` 주석(`attach v1 → null`) 갱신(attach에서 non-null).
- docs/26 "v1 한계 #3" 제거.

## Definition of Done

- [ ] E2E 수용 1~4 green (attach + `{{wiremock}}` → inventory CapturedHttpCall; 토큰 401 단위; 기존 attach e2e 회귀).
- [ ] 단위: `HttpCaptureServer` 토큰 필터(접두사 검증 401 / strip 후 stub 매칭 / 드레인 URL 토큰 미포함) + `hostBaseUrl`; `OverrideComposeGenerator` attach 항상 host-gateway + 치환 env; `AttachedComposeEnvironment.httpCapture()` non-null + close 시 둘 다 stop; runAttached 실패 시 서버 정리.
- [ ] 전체 회귀(`./gradlew test`) green — analysis/기존 attach 무변경.
- [ ] docs/26 갱신(한계 #3 제거, 외부 HTTP 캡처 + host.docker.internal + per-run 토큰 + Docker 20.10+ 한계) + `docs/decisions/mock-services-security-model.md`에 attach 호스트 WireMock의 0.0.0.0+토큰 예외 기록.
- [ ] PR 전 spec-compliance + 코드 품질 리뷰 트리아지.
