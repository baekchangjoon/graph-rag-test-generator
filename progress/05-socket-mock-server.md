# Progress: socket-mock-server 골격 TDD 구현

**Date**: 2026-05-25
**Task**: #7 socket-mock-server 골격 TDD 구현

## 산출물

### 모듈 (`socket-mock-server`)

```
src/main/java/io/graphrag/socketmock/
├── SocketMockApplication.java            # @SpringBootApplication
├── api/
│   ├── ExpectationRequest.java           # admin POST body
│   └── AdminController.java              # /__admin/expectations 등
├── domain/
│   └── Expectation.java                  # record + Builder + hex parsing
├── registry/
│   └── ExpectationRegistry.java          # ConcurrentMap, prefix 매칭
├── server/
│   ├── MockChannelHandler.java           # Netty inbound handler
│   └── NettyServerManager.java           # 포트별 TCP 서버 idempotent 바인딩
└── config/
    └── JacksonConfig.java
```

### 테스트
- `ExpectationRegistryTest` (6) — 등록/매칭/세션 제거/clear
- `MockChannelHandlerTest` (2) — EmbeddedChannel로 패턴 매칭 검증
- `NettyServerManagerTest` (3) — 실 TCP 바인딩 + 클라이언트 소켓 통신
- `AdminApiTest` (3) — Spring Boot @SpringBootTest + MockMvc

## 설계 결정

- **Netty 기반**: 표준 오픈소스 byte 프로토콜 mock 부재로 자체 제작
- **idempotent ensureBound**: 같은 포트에 두 번 호출해도 안전
- **Spring DI**: ExpectationRegistry + NettyServerManager 자동 와이어링
- **`ByteArrayDecoder` + MockChannelHandler**: Netty pipeline에 byte 변환 단계 추가
- **Phase 0 매칭은 prefix only**: 정규식/마스킹은 Phase 4
- **stateful 세션 미구현**: stepOrder 필드는 보존, 매칭 로직은 lowest stepOrder 우선

## 발견 및 수정

1. **테스트 hex 1자리**: "A", "B" 잘못된 hex → "0A", "0B"로 수정
2. **포트 충돌**: 고정 포트(9000) 사용 시 BindException → ServerSocket(0)로 ephemeral 포트 할당
3. **CI 환경**: 모든 테스트가 localhost ephemeral 포트 사용, 충돌 없음

## 의도적으로 후속 phase로 미룬 항목

- Stateful multi-step 매칭 (현재 stepOrder 필드만 보존)
- Recording 모드 (수신 byte hex dump)
- 정규식/마스크 매칭
- UDP 지원 (현재 TCP만)
- 그래프 RAG와 연동되는 자동 시나리오 import

## 검증

`./gradlew :socket-mock-server:test` 결과: (테스트 실행 결과 결과 확인 후 갱신)

## 다음 단계

Task #8 — graph-rag-builder Phase 0 TDD.
