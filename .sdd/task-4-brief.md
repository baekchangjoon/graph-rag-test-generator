### Task 4: EndpointExplorationRunner 및 캡처 파이프라인 연동 (`graph-rag-builder`)

**Files:**
- Modify: 
  * `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`
  * `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InvocationOutcome.java`
  * `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
- Test: Modify: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/OtelHttpCaptureAcceptanceTest.java` (또는 해당 모듈의 통합 테스트)

**Interfaces:**
- Consumes: `KafkaCaptureReceiver`
- Produces: 
  * `InvocationOutcome` (List<CapturedEventEmit> 포함)
  * `ExploredPath` (이벤트 캡처 완료 기록 반영)

- [ ] **Step 1: Write the failing test**
  `OtelHttpCaptureAcceptanceTest.java`에 HTTP API 요청을 통해 SUT가 메시지를 발행할 때, `EndpointExplorationRunner`가 그 이벤트를 `CapturedEventEmit`으로 회수하는 통합 테스트 케이스를 설계하여 추가합니다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.capture.OtelHttpCaptureAcceptanceTest`
  Expected: FAIL

- [ ] **Step 3: Write minimal implementation**
  * `EndpointExplorationRunner`에 `KafkaCaptureReceiver` 멤버를 생성자 주입으로 연동합니다.
  * `doSend()` 내에서 HTTP 요청 실행 후 `receiver.drain(traceId, AWAIT_TIMEOUT_MILLIS)`을 실행해 `CapturedEventEmit` 목록을 회수합니다.
  * 수집한 이벤트를 `InvocationOutcome`에 실어 `ExploredPath` 필드 및 `GraphAsset` 누적 리스트에 머지하는 흐름을 배선합니다.
  * `BuilderCli`에서 `KafkaCaptureReceiver`를 정상 기동(`start`)하고 종료(`close`)하도록 수명주기를 관리합니다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.capture.OtelHttpCaptureAcceptanceTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add graph-rag-builder/src/main/java/io/graphrag/builder/
  git commit -m "feat: wire KafkaCaptureReceiver with EndpointExplorationRunner to capture outbound events"
  ```

---

