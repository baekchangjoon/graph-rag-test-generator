# Progress: 잔여 항목 5개 일괄 완료

**Date**: 2026-05-26
**Tasks**: #38-#42

## 산출

### #38 GraphArchive HTTP 영속 + CLI archive 모드

**graph-rag-builder**:
- `GraphArchive`에 `captured_http.json` 추가 + `addCapturedHttpCall`/`capturedHttpByPath`
- save/load 라운드트립 보존

**test-generator**:
- `archive/ArchiveReader` — 4개 JSON 파일 (endpoints, paths, captured_sql, captured_http) 직접 읽음
- `buildInput(endpointId, pkg)` → `MultiPathSynthesisInput`
- CLI 2 모드 지원: `--spec` (Phase 2 기존) 또는 `--archive --endpoint --package`

테스트: GraphArchive HTTP 라운드트립(2) + ArchiveReader(3) + 기존 CLI(4)

### #39 STOMP 자동 캡처

**graph-rag-builder/capture/ws/StompCaptureInterceptor**:
- `ChannelInterceptor` 구현. inbound/outbound STOMP 채널에 부착
- `StompCommand.SEND/MESSAGE/SUBSCRIBE`만 캡처 (CONNECT/DISCONNECT 등 control은 무시)
- 활성 `CaptureContext`에 `CapturedWsMessage` 추가
- `CaptureContext`에 `capturedWsMessages()` 누적기 추가

테스트: SEND/MESSAGE/SUBSCRIBE 캡처, control frame 무시, context 미설정 시 no-op (5)

### #40 실 Socket InputStream/OutputStream 후킹

**socket-capture-agent**:
- `RecordingInputStream` / `RecordingOutputStream` — delegate stream wrap + ByteSink callback
- `SocketByteRecorder.wrap(Socket)` → 양쪽 stream + 누적 buffer 반환
- `toHex(byte[])` 유틸 ("01 02 FF" 포맷)

테스트:
- RecordingInputStream/OutputStream 단위 (3)
- 실 `java.net.Socket` + `ServerSocket` 페어로 read/write 검증 (1)
- `toHex` 포맷 (1)

`SocketByteRecorder.wrap`은 명시적 wrap 모델 — system class 자동 instrumentation은 boot classpath 부착이 필요해 별도 Phase.

### #41 Coverage-guided fuzzer (간이)

**graph-rag-builder/exploration**:
- `CoverageSignature` — coverage 해시 record (실 JaCoCo 또는 응답 hash 등 caller가 결정)
- `CoverageGuidedFuzzer implements PathExplorer`
  - seed inputs로 시작
  - body Map의 Number/String 필드를 mutate
  - novelty filter: 같은 signature 입력은 제외
  - budget (maxInputs + timeLimit) 준수
  - random seed 고정 → 결정적

테스트: seed 보존, novelty 필터, mutation 확장, budget 준수, 결정성 (6)

실 JaCoCo runtime 부착은 호출자가 scorer 함수 안에서 구현 — 본 클래스는 generic 엔진.

### #42 Neo4j GraphStore

**graph-rag-builder/store**:
- `GraphStore` SPI — Endpoint/Path/Sql/HttpCall save+query
- `FileGraphStore` — 기존 `GraphArchive` 위에 SPI 구현
- `Neo4jGraphStore` — Neo4j Java driver 5.26 기반
  - 스키마: `(:Endpoint)-[:HAS_PATH]->(:Path)-[:EXECUTED]->(:Sql)`,
    `(:Path)-[:INVOKED]->(:HttpCall)`
  - 객체 detail은 JSON `payload` 속성에 저장 (스키마 진화 대응)

테스트:
- `FileGraphStoreTest` (2) — endpoint/path/sql/http 라운드트립, empty 케이스
- `Neo4jGraphStoreIntegrationTest` (3) — Testcontainers Neo4j 사용. Docker 없는 환경에서는 `GRAPH_RAG_NEO4J_TEST=1` env 없을 시 SKIPPED.

`GRAPH_RAG_NEO4J_TEST=1 ./gradlew :graph-rag-builder:test --tests "*Neo4j*"` 로 실 통합 실행.

## 검증

`./gradlew build`: BUILD SUCCESSFUL.

## 모듈별 누적 테스트 (Phase 0-6)

```
shared-model               — 16 test 클래스
testlib-api                — 2
testlib-adapter-noop       — 1
test-state-dashboard       — 4
socket-mock-server         — 4
graph-rag-builder          — 16+ (3 SKIPPED for Neo4j without Docker)
test-generator             — 14
socket-capture-agent       — 2
samples/demo-sut           — 4 (Phase 0/1/2/3 E2E)
```

## 잔여 (실 인프라 접근 필요)

- **5M 레거시 PoC**: 운영 환경 접근 시 docs/10-legacy-scaling.md 절차대로 Phase 6a→6e 진행
- **WireMock OpenAPI 응답 합성**: 외부 시스템의 OpenAPI 사양 입수 시
- **Socket 프로토콜 디코더 등록**: 사양 입수 시 ProtocolDecoder 구현
- **실 java.net.Socket auto-instrument**: Boot-Class-Path 설정 + bootstrap classloader 작업 필요 (별도 작업)
