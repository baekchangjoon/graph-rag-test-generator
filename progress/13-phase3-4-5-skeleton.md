# Progress: Phase 3 (WebSocket), Phase 4 (Netty Socket), Phase 5 (Raw Socket) 스켈레톤

**Date**: 2026-05-25
**Tasks**: #25, #26, #27

## Phase 3 — WebSocket/STOMP (shared-model)

추가된 도메인 객체:
- `WsMessageDirection` enum (INBOUND/OUTBOUND)
- `WsEndpointStyle` enum (STOMP/RAW/JSR356)
- `CapturedWsMessage` record

테스트: `CapturedWsMessageTest` (2) — JSON 라운드트립 + enum 완전성.

### 미구현 (deferred to runtime Phase 3+):
- 실제 WebSocket 핸들러 분석 (`@MessageMapping`)
- STOMP 메시지 캡처 인프라
- WebSocket stub composer
- demo-sut의 WebSocket endpoint

## Phase 4 — Netty Socket (shared-model + test-generator + builder)

### shared-model 추가
- `SocketDirection` enum
- `SocketProtocol` enum (TCP/UDP)
- `SocketFramework` enum (NETTY/RAW_SOCKET/OTHER)
- `CapturedSocketIO` record (host, port, hex, decoded, sessionField)

테스트: `CapturedSocketIOTest` (2).

### test-generator
- `compose/socket/SocketMockComposer` — `CapturedSocketIO` 시퀀스 → testlib `socketMock.bind(...)` 코드

테스트: `SocketMockComposerTest` (4) — bind, withSessionField, no-inbound, deterministic.

### graph-rag-builder
- `capture/socket/ProtocolDecoder` SPI
- `capture/socket/ProtocolDecoderRegistry` — endpoint별 매칭

테스트: `ProtocolDecoderRegistryTest` (3).

### 미구현 (deferred to Phase 4+):
- Netty pipeline 분석기 (`ChannelInitializer.initChannel()` 본문 read → ByteLayout 추출)
- 실제 socket-mock-server 통합 E2E (분석 시점 capture)
- ByteLayout 자동 추출

## Phase 5 — Raw Socket javaagent (graph-rag-builder)

`ProtocolDecoder` + `ProtocolDecoderRegistry` (Phase 4와 공유)가 raw socket 사양 등록의 메커니즘.

### 미구현 (deferred):
- javaagent 본체 (`premain` + `Instrumentation`)
- InputStream/OutputStream 후킹 (bytecode 변환)
- `@WireFormat`/`@WireField` 어노테이션 처리
- raw socket E2E (`java.net.Socket`을 사용하는 SUT 케이스)

## 누적 검증

`./gradlew build`: BUILD SUCCESSFUL.

추가된 단위 테스트 (Phase 3-5):
- CapturedWsMessageTest (2)
- CapturedSocketIOTest (2)
- SocketMockComposerTest (4)
- ProtocolDecoderRegistryTest (3)
→ 11 new

## 설계와의 부합

| 항목 | 결과 |
|---|---|
| SCHEMAS.md 0절 `CapturedSocketIO`, WebSocket 타입 | OK (record + JSON 라운드트립) |
| docs/07 socket mock 합성 | OK (SocketMockComposer) |
| docs/03 protocol decoder 자리 비워두기 | OK (ProtocolDecoderRegistry 빈 상태로 초기화) |

## Phase 3-5에서 일관되게 미룬 항목

이들은 운영 환경에서의 검증 + 추가 인프라가 필요해 별도 작업으로 분리:

1. **Netty pipeline 정적 분석**: scip-java + Spoon 결합 작업
2. **javaagent bytecode instrumentation**: ASM/ByteBuddy 도입
3. **WebSocket E2E**: SUT의 STOMP endpoint + 분석 환경에서 STOMP 클라이언트 모킹
4. **raw socket E2E**: `java.net.Socket` 직접 사용 SUT 케이스
5. **demo-sut의 WebSocket/Socket endpoint**: phase별 별도 시나리오 추가

## 다음 단계

Phase 6 — 5M 라인 레거시 이식. docs/10-legacy-scaling.md 작성 완료. 실 인프라 구축은 별도 운영 작업.
