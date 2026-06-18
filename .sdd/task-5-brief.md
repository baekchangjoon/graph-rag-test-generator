### Task 5: testlib KafkaHelper 확장 및 의존성 추가 (`testlib` / `e2e`)

**Files:**
- Modify: 
  * `testlib/src/main/java/io/graphrag/testlib/api/KafkaHelper.java`
  * `testlib/build.gradle.kts`

**Interfaces:**
- Consumes: None
- Produces:
  * `KafkaHelper.subscribe(String topic)`
  * `KafkaHelper.consumeNextRecord(String topic, Duration timeout)`

- [ ] **Step 1: Write the failing test**
  `testlib` 단위 테스트에 `subscribe` 개시 후 메시지를 수신하여 비동기 `consumeNextRecord` 검증이 통과하고, `JSONAssert`가 정상적으로 로드되는지 검증하는 테스트를 추가합니다.
- [ ] **Step 2: Run test to verify it fails**
  Expected: FAIL (컴파일 에러: `consumeNextRecord` 및 `subscribe` 미존재)

- [ ] **Step 3: Write minimal implementation**
  * `testlib/build.gradle.kts`에 `org.skyscreamer:jsonassert` 의존성을 `api` 또는 `implementation`으로 추가합니다.
  * `KafkaHelper.java`에 `subscribe(topic)` 및 `consumeNextRecord(topic, timeout)` 메서드를 구현합니다. 매 테스트 격리를 위해 고유한 임시 `groupId`로 토픽을 구독하여 백그라운드 리스너로 유입된 레코드를 버퍼링 후 대기 반환하도록 구현합니다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :testlib:test`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add testlib/
  git commit -m "feat: extend KafkaHelper with async pre-subscribe and add jsonassert dependency"
  ```

---

