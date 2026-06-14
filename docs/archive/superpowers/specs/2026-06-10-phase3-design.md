# 2026-06-10 — Phase 3 설계: WebSocket / STOMP

기준: docs/09 (3.1~3.3), docs/01 (in-scope: Spring STOMP).

## 목표

Spring STOMP endpoint의 메시지 교환을 그래프 사실로 캡처하고, 생성 테스트가
STOMP로 메시지를 보내 응답을 검증해 compose 환경에서 통과한다.

## SUT 확장

order-service에 STOMP 추가: WS endpoint `/ws` (plain WebSocket), app prefix
`/app`, simple broker `/topic`.
`@MessageMapping("/orders/count")` + `@SendTo("/topic/orders")` —
payload `{userId}` → 응답 `{userId, count}` (JPA 파생 쿼리로 주문 수 조회 —
DB sink 캡처까지 관통).

## 단계별 설계

### 3.1 WsEndpoint 인덱싱

- Spoon으로 `@MessageMapping`/`@SendTo` 메서드 + payload 타입(BodyShape 재사용) 수집
- WS 설정도 정적 추출: `addEndpoint("/ws")`, `setApplicationDestinationPrefixes("/app")`
  리터럴 (best-effort)
- 사실: `WsEndpoint(id, wsPath, appPrefix, destination, sendTo, handlerClass,
  handlerMethod, payloadType)` — id는 `ws-<destination>` 정규화

### 3.2 STOMP 캡처

- **자체 최소 STOMP 클라이언트** (JDK `java.net.http.WebSocket` 기반, 의존성 0):
  CONNECT/SUBSCRIBE/SEND/MESSAGE 프레임만. spring-messaging 클라이언트 의존은
  도구가 SUT 스택에 끌려가는 것이라 배제 (socket-mock 자체 제작과 같은 결).
- 캡처 흐름: FK seed → 결정적 payload 2종(happy + `<x>Id` missing-ref 변형) →
  sendTo 토픽 구독 → SEND → 응답 MESSAGE + SQL 로그 구간 캡처 →
  `WsExchange(id, wsEndpointId, payload, responseDestination, response, capturedSqlIds)`
- **WS 분기 탐색은 보류**: Phase 1 탐색기는 HTTP status 기반 path 식별 전제.
  WS의 "path"는 응답 메시지 shape 기준으로 별도 설계가 필요 — 결정적 2-변형
  캡처로 시작하고, 실수요 확인 후 확장 (decision doc 기록)

### 3.3 testlib STOMP helper + 생성 테스트

- `StompHelper` (testlib api): `scope.stomp(wsPath)` → connect/subscribe/send/
  `awaitMessageContaining(marker, timeout)`. cleanup 시 연결 종료
- 생성 테스트의 **병렬 격리**: broker 토픽은 broadcast이므로, 응답에 echo되는
  치환 값(testId-unique)을 마커로 자기 메시지만 기다린다.
  응답이 payload의 API_PARAM을 echo하지 않으면 상관관계 불가 →
  `@Execution(SAME_THREAD)` + `serial_required(WS_NO_CORRELATION)`
- 픽스처/치환은 FixtureComposer 재사용 (WsExchange → pseudo-ExploredPath 변환)
- 전용 템플릿 `ws-test-class.mustache`: 구독 → 전송 → 마커 대기 → JSON 필드 단언

## shared-model 확장

`WsEndpoint`, `WsExchange`, `GraphAsset.wsEndpoints/wsExchanges` (후방 호환 normalize)

## 성공 기준

- [ ] 그래프에 WsEndpoint + WsExchange(happy/missing-ref) + 연계 SQL 캡처
- [ ] 생성 WS 테스트가 compose에서 통과 (병렬 마커 격리 포함)
- [ ] 기존 14개 HTTP 테스트 비회귀, `gradlew check` GREEN
