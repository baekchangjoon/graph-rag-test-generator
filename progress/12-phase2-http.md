# Progress: Phase 2 외부 HTTP 캡처 + WireMock 합성

**Date**: 2026-05-25
**Tasks**: #20-#24
**Result**: 외부 HTTP 호출 캡처 → CapturedHttpCall → WireMock stub 합성 E2E 통과

## 산출물

### shared-model 추가 (#20)
- `HttpClientType` enum (REST_TEMPLATE, WEBCLIENT, FEIGN, OKHTTP, OTHER)
- `CapturedHttpCall` record + 테스트 (4)

### demo-sut 확장 (#21)
- `InventoryClient` (RestClient 기반, `external.inventory.url` 환경변수)
- `OrdersController.createWithInventory` — POST /api/orders/with-inventory
  - 사용자 검증 (404)
  - amount 검증 (400)
  - 외부 inventory 호출
  - 재고 부족 시 409
  - 성공 시 201 + status="RESERVED"

### graph-rag-builder 캡처 (#22)
- `CaptureContext`에 `capturedHttpCalls()` + `addCapturedHttpCall` 추가
- `capture/http/WireMockHttpRecorder` — WireMockServer.getAllServeEvents() 변환
  - `captureAll(pathId, target)` — 직접 회수
  - `captureIntoContext(target)` — 활성 컨텍스트로 흘림
- 테스트 4: GET 캡처, 다중 호출 순서, 컨텍스트 흘리기, empty 케이스

### test-generator stub composer (#23)
- `compose/http/HttpStubComposer.compose(CapturedHttpCall)` → Java 코드 라인
  - 출력 예: `stubFor(get(urlPathEqualTo("/...")).withQueryParam("...", equalTo("..."))....willReturn(aResponse().withStatus(200).withBody("...")));`
  - URL query → withQueryParam (TreeMap key 정렬로 결정적)
  - GET/POST/PUT 등 HTTP 메소드 지원
- 테스트 5: GET, POST, no-query, multiple, deterministic

### Phase 2 E2E (#24)
`Phase2HttpE2eTest`가 다음을 검증:
1. WireMock 시작 + inventory stub 등록
2. demo-sut의 /api/orders/with-inventory 호출
3. CaptureContext + ProxyDataSource로 SQL + WireMockHttpRecorder로 HTTP 캡처
4. `CapturedHttpCall.urlConcrete`에 `/inventory/stock?type=EXPRESS` 포함
5. `HttpStubComposer.compose(call)`이 valid WireMock 코드 생성
6. INSERT INTO orders 도 함께 캡처 (SQL + HTTP 동시 캡처 확인)
7. 재고 부족 분기 (409) 별도 검증

## 검증

`./gradlew build`: BUILD SUCCESSFUL. 누적 171 tests PASSED.

추가 테스트 (16):
- `CapturedHttpCallTest` (4)
- `WireMockHttpRecorderTest` (4)
- `HttpStubComposerTest` (5)
- `Phase2HttpE2eTest` (2)
- (실제 다른 모듈에서 일부 재실행되어 카운트가 누적)

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| docs/03 capture 레이어 — HTTP 클라이언트 추적 | OK (WireMock 기반 recorder) |
| docs/04 mock 합성 — WireMock stub | OK (HttpStubComposer) |
| SCHEMAS.md 0절 CapturedHttpCall | OK (record + 테스트 통과) |
| SUT 무수정 + 외부 시스템 mock 교체 | OK (`external.inventory.url` env로 redirect) |

## Phase 2 의도적으로 미룬 항목

- **OTEL javaagent + baggage propagation**: 분석/실행 양쪽 자동 부착. 합의된 design이나 구현은 docker-compose 통합 시.
- **응답 필드 사용 추적**: SUT가 response의 어느 필드를 읽는지 추적 → minimal mock 응답. 정적 분석 또는 동적 byte-code instrumentation 필요.
- **WireMock stub의 TestSynthesizer 통합**: 현재는 composer만. TestSynthesizer가 자동 임포트/setup하는 코드는 Phase 2+.
- **OpenAPI 사양 기반 응답 합성 (sparse → full)**: OPEN-DECISIONS에서 default로 fallback heuristic만.

## 다음

- Phase 3 (WebSocket/STOMP) 스켈레톤
- Phase 4 (Netty socket) 보강
- Phase 5 (raw socket javaagent) 스켈레톤
- Phase 6 (5M 레거시 아키텍처) 문서
