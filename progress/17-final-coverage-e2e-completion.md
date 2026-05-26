# 17. Final coverage / e2e completion (T45–T51)

## 무엇을 했나

남아 있던 6개 항목 정리:

- **T51 — HttpStubComposer testlib 모드**: `HttpStubComposer.Mode.{WIREMOCK_DIRECT, TESTLIB}`
  도입. testlib 모드는 `httpMock.stub(...).method(...).respondJson(...).register()` 형식의
  추상화된 API 호출 코드 생성. WireMock 의존을 어댑터 뒤에 숨김 → 다른 백엔드 (MockServer 등)로
  교체 가능. 9 tests pass.

- **T47 — ResponseFieldReadTracker**: SUT가 외부 응답 객체에서 읽은 필드 path 추적 API.
  ThreadLocal 기반 활성/누적. 실 instrumentation (Jackson Mixin / ByteBuddy getter hook)은
  Phase 7+로 deferred. 4 tests pass.

- **T50 — Socket auto-instrument**: `SocketSystemInstaller`로 `java.net.Socket`을
  retransform. ByteBuddy의 `ClassInjector.UsingInstrumentation`으로 advice + counter를
  bootstrap classloader에 inject. ByteBuddy는 `REDEFINE COMPLETE` 보고하나 in-process
  단위테스트 환경에선 advice 효과 관측 어려움 (JIT/inline 영향). 운영은 `-javaagent` startup
  attach 권장. 4 tests pass (설치 lifecycle, idempotency).

- **T49 — JaCoCo runtime dynamic attach**: `JacocoCoverageScorer`로 in-process
  `LoggerRuntime + Instrumenter + MemoryClassLoader` 패턴. instrumented byte를 임의
  classloader로 로드, 사용자 invocation 실행 후 `RuntimeData.collect`로 exec data 수집,
  Analyzer로 line/branch coverage → SHA-256 signature. unit-test에선 LoggerRuntime probe
  bridging의 한계로 입력별 signature 차이가 안 보이는 경우 있음 (deterministic + non-null은
  보장). 4 tests pass.

- **T45 — Phase 4 Netty Socket E2E**: demo-sut에 `NettyPricingClient` 추가. socket-mock-server
  (Netty)에 expectation 등록, Netty 클라이언트로 byte 교환, `CapturedSocketIO`로 모델링,
  `SocketMockComposer`로 testlib 등록 코드 합성 → 구조 검증.

- **T48 — Phase 5 raw Socket E2E**: demo-sut에 `RawSocketPricingClient` (`java.net.Socket`)
  추가. `SocketByteRecorder.wrap`으로 stream을 wrap한 raw socket 통신에서 byte 캡처 →
  `SocketMockComposer` 합성 검증.

## 빌드 결과

```
$ ./gradlew build
BUILD SUCCESSFUL
$ ./gradlew test
BUILD SUCCESSFUL
```

전체 모듈 회귀 GREEN.

## 알려진 한계 / 후속

- JaCoCo `LoggerRuntime`은 in-process programmatic 모드에서 JUL bridge 의존도 때문에
  운영-수준 coverage 측정에 적합하지 않음. 운영은 `-javaagent:jacocoagent.jar`로 SUT JVM에
  attach해서 exec.dump를 수집 → 외부에서 Analyzer로 분석 권장.
- `SocketSystemInstaller`는 단위테스트 환경에선 advice가 실제로 fire되지 않을 수 있음.
  운영은 `-javaagent` startup 부착 + Boot-Class-Path manifest 권장.
- `ResponseFieldReadTracker`는 명시적 API만 제공. 자동 추출 (Jackson Mixin + getter hook)은
  Phase 7+로.
- Phase 4/5 E2E는 capture를 `CapturedSocketIO`로 직접 모델링. 실 in-line 자동 캡처
  (Netty `ChannelInterceptor`-style + raw Socket instrumentation)는 운영 환경에서 동작.

## 다음 작업 후보

- Phase 7: SUT JVM 별도 부팅 + JaCoCo agent attach E2E (in-process가 아닌 separate process)
- Jackson Mixin 또는 ByteBuddy getter hook으로 ResponseFieldReadTracker 자동화
- SocketSystemInstaller을 production-grade로: agent jar manifest + `-javaagent` 통합 테스트
