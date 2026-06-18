### Task 3: KafkaCaptureReceiver 신설 (`graph-rag-builder`)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureReceiver.java`
- Test: Create: `graph-rag-builder/src/test/java/io/graphrag/builder/run/KafkaCaptureReceiverTest.java`

**Interfaces:**
- Consumes: None
- Produces:
  * `KafkaCaptureReceiver(String bootstrapServers)`
  * `KafkaCaptureReceiver.start()`
  * `KafkaCaptureReceiver.drain(String traceId, long timeoutMillis)`
  * `KafkaCaptureReceiver.close()`

- [ ] **Step 1: Write the failing test**
  `KafkaCaptureReceiverTest.java`를 생성하고, 카프카 브로커에 발행된 레코드(헤더에 `traceparent`를 가진)를 캡처하고 `drain`하여 정상 수집하는 단위 테스트를 작성합니다. (OOM 방지 큐 크기 제한 및 내부 토픽 필터링 검증 포함)
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.KafkaCaptureReceiverTest`
  Expected: FAIL (컴파일 에러: `KafkaCaptureReceiver` 미존재)

- [ ] **Step 3: Write minimal implementation**
  `KafkaCaptureReceiver.java`를 작성합니다.
  * `AdminClient`를 사용해 `__`로 시작하는 토픽을 제외한 정규식으로 `Consumer`를 구동합니다.
  * 백그라운드 스레드에서 최대 10,000개 크기의 Capped Queue에 `ConsumerRecord`를 적재합니다.
  * Null Tombstone이나 비-JSON 본문 유입 시 `TextNode` 등으로 안전하게 감싸서 방어 코딩을 수행합니다.
  * `drain` 메서드에서 헤더의 `traceId` 매핑 레코드를 필터링하여 반환합니다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.KafkaCaptureReceiverTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureReceiver.java
  git commit -m "feat: implement KafkaCaptureReceiver with capped queue and topic regex filtering"
  ```

---

