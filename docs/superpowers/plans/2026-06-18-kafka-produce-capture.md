# 카프카 아웃바운드 발행(Produce) 캡처 및 검증 구현 계획서

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SUT가 API 요청을 받아 처리하는 도중 외부 카프카 토픽에 메시지를 발행(Produce)하는 아웃바운드 이벤트를 OTEL traceId와 브로커 임시 구독(하이브리드 방식)으로 캡처하고, 생성되는 통합 테스트 코드에 단언문(Assertion)을 자동 합성한다.

**Architecture:** 
1. `shared-model`에 `CapturedEventEmit` 데이터 모델을 신설하고 `ExploredPath`와 `GraphAsset`에 필드를 추가한다. (구 버전 하위 호환 오버로드 생성자 구현 필수)
2. 빌더 기동 시 백그라운드에서 `KafkaCaptureReceiver`가 토픽을 구독하여 유입되는 메시지를 Capped Queue(OOM 방지)에 적재하고, API 응답 완료 시 `traceId`가 매핑되는 메시지를 drain한다.
3. `test-generator`의 `Generator` 및 `test-class.mustache` 템플릿을 확장하여, API 호출 전 미리 `subscribe`를 등록(Latest Offset Race 방지)하고 API 호출 후 `consumeNextRecord`와 `JSONAssert`를 사용해 토픽, 키, 페이로드를 단언 검증하는 Java 코드를 스코프 격리 블록 `{ ... }` 내에 합성한다.

**Tech Stack:** Java 17, Spring Boot, Spring Kafka Test, Apache Kafka Client, Jackson, Mustache, org.skyscreamer:jsonassert

## Global Constraints
- `io.graphrag.model.CapturedEventEmit`은 `record` 구조로 생성되어야 하며 Jackson 라운드트립이 가능해야 한다.
- 기존 그래프 JSON 역직렬화 호환성을 보장하기 위해 `ExploredPath`와 `GraphAsset`에 구 버전 파라미터 개수를 지닌 오버로드 생성자를 명시해야 한다.
- 카프카 메시지 캡처 버퍼인 `ConcurrentLinkedQueue`는 OOM 방지를 위해 최대 10,000개의 크기 한도(Capped)를 지녀야 하며 초과 시 오래된 메시지를 제거한다.
- 카프카 내부 관리 토픽(`__consumer_offsets` 등)은 와일드카드 구독 시 필터링 정규식(`^(?!_).+`)을 통해 제외되어야 한다.

---

### Task 1: 데이터 모델 추가 및 호환성 생성자 구현 (`shared-model`)

**Files:**
- Create: `shared-model/src/main/java/io/graphrag/model/CapturedEventEmit.java`
- Modify: 
  * `shared-model/src/main/java/io/graphrag/model/ExploredPath.java`
  * `shared-model/src/main/java/io/graphrag/model/GraphAsset.java`
- Test: `shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java`

**Interfaces:**
- Consumes: None
- Produces: 
  * `io.graphrag.model.CapturedEventEmit`
  * `ExploredPath` (13-argument constructor 및 구 12-argument 호환 생성자)
  * `GraphAsset` (14-argument constructor 및 구 13-argument 호환 생성자)

- [ ] **Step 1: Write the failing test**
  `shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java` 파일에 `CapturedEventEmit` 라운드트립 검증과 `ExploredPath`/`GraphAsset` 구 버전 직렬화 호환 테스트 코드를 작성합니다.
  ```java
  @Test
  void testCapturedEventEmitRoundTrip() throws Exception {
      CapturedEventEmit emit = new CapturedEventEmit("emit-1", "path-1", "order-topic", "user-1", Json.mapper().readTree("{\"status\":\"OK\"}"));
      String json = Json.mapper().writeValueAsString(emit);
      CapturedEventEmit read = Json.mapper().readValue(json, CapturedEventEmit.class);
      assertEquals("emit-1", read.id());
      assertEquals("path-1", read.pathId());
      assertEquals("order-topic", read.topic());
      assertEquals("user-1", read.key());
      assertEquals("OK", read.payload().get("status").asText());
  }
  ```
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :shared-model:test --tests io.graphrag.model.JsonRoundTripTest`
  Expected: FAIL (컴파일 에러: `CapturedEventEmit` 클래스 미존재)

- [ ] **Step 3: Write minimal implementation**
  `CapturedEventEmit.java`를 생성하고, `ExploredPath.java` 및 `GraphAsset.java`에 새 필드 추가와 구 버전 인스턴스 호환용 생성자를 구현합니다.
  * **CapturedEventEmit.java**:
    ```java
    package io.graphrag.model;
    import com.fasterxml.jackson.databind.JsonNode;
    public record CapturedEventEmit(String id, String pathId, String topic, String key, JsonNode payload) {}
    ```
  * **ExploredPath.java**:
    ```java
    // 13-argument canonical constructor 구현 및 아래 구 버전 12-argument 오버로딩 추가
    public ExploredPath(String id, String endpointId, JsonNode sampleInput, int expectedStatus,
                        JsonNode sampleResponse, List<String> capturedSqlIds, List<String> capturedHttpCallIds,
                        List<BranchRef> branchesTaken, String discoveredBy, List<String> constraints,
                        List<String> validationWarnings, List<String> requiredSeedIds) {
        this(id, endpointId, sampleInput, expectedStatus, sampleResponse, capturedSqlIds, capturedHttpCallIds,
             branchesTaken, discoveredBy, constraints, validationWarnings, requiredSeedIds, List.of());
    }
    ```
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :shared-model:test --tests io.graphrag.model.JsonRoundTripTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add shared-model/src/main/java/io/graphrag/model/
  git commit -m "feat: add CapturedEventEmit and update models with compatibility constructors"
  ```

---

### Task 2: PartitionedGraphStore 및 IncrementalPlan 연동 (`graph-rag-builder`)

**Files:**
- Modify: 
  * `graph-rag-builder/src/main/java/io/graphrag/builder/store/PartitionedGraphStore.java`
  * `graph-rag-builder/src/main/java/io/graphrag/builder/cli/IncrementalPlan.java`
  * `graph-rag-builder/src/main/java/io/graphrag/builder/cli/IncrementalBuildPlanner.java`
  * `shared-model/src/main/java/io/graphrag/model/GraphRagClient.java`
  * `graph-rag-builder/src/main/java/io/graphrag/builder/store/FileGraphRagClient.java`
- Test: 
  * `graph-rag-builder/src/test/java/io/graphrag/builder/store/PartitionedGraphStoreTest.java`
  * `graph-rag-builder/src/test/java/io/graphrag/builder/cli/IncrementalBuildPlannerTest.java`

**Interfaces:**
- Consumes: `io.graphrag.model.CapturedEventEmit`
- Produces: 
  * `PartitionedGraphStore.load` / `save` (CapturedEventEmit 파티셔닝 지원)
  * `IncrementalPlan.carriedEventEmits`
  * `GraphRagClient.capturedEventEmitsForPath(String pathId)`

- [ ] **Step 1: Write the failing test**
  `PartitionedGraphStoreTest.java`에 `CapturedEventEmit`이 포함된 `GraphAsset`을 파티션 샤드 파일로 정상적으로 영속 저장하고 로드하여 병합해 복원하는지 검증하는 테스트를 추가합니다.
  ```java
  @Test
  void testSaveAndLoadCapturedEventEmits() {
      // CapturedEventEmit을 포함한 임의의 GraphAsset을 구성하고 partitionedStore.save() 후 load() 하여 검증
  }
  ```
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.store.PartitionedGraphStoreTest`
  Expected: FAIL

- [ ] **Step 3: Write minimal implementation**
  `PartitionedGraphStore.java`의 `save`와 `load` 로직에 `capturedEventEmits`를 `pathId` 파일 기준으로 샤딩하여 JSON으로 저장하고 불러와 병합하는 로직을 구현합니다. `IncrementalPlan` 및 `IncrementalBuildPlanner`에 이월 로직을 추가하고 `FileGraphRagClient`에 `capturedEventEmitsForPath` 메서드를 오버라이드하여 구현합니다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :graph-rag-builder:test`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add graph-rag-builder/src/main/java/io/graphrag/builder/
  git commit -m "feat: integrate CapturedEventEmit into PartitionedGraphStore and incremental planner"
  ```

---

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

### Task 6: Test Generator 및 Mustache 템플릿 확장 (`test-generator`)

**Files:**
- Modify: 
  * `test-generator/src/main/java/io/graphrag/generator/Generator.java`
  * `test-generator/src/main/resources/templates/test-class.mustache` (또는 관련 HTTP 테스트 생성 템플릿)
- Test: Modify: `test-generator/src/test/java/io/graphrag/generator/GeneratorTest.java`

**Interfaces:**
- Consumes: `CapturedEventEmit`
- Produces: 
  * `Generator.generate` (카프카 단언문이 포함된 Java 테스트 클래스 코드 파일 합성)

- [ ] **Step 1: Write the failing test**
  `GeneratorTest.java`에 `CapturedEventEmit`이 존재하는 `ExploredPath`를 입력으로 넣어 테스트 소스 코드를 생성했을 때, `kafkaHelper.subscribe`, `kafkaHelper.consumeNextRecord` 및 `JSONAssert` 단언 코드가 격리된 `{ ... }` 중괄호 블록 내에 이스케이프되어 올바르게 합성되는지 단언하는 테스트 케이스를 추가합니다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :test-generator:test --tests io.graphrag.generator.GeneratorTest`
  Expected: FAIL

- [ ] **Step 3: Write minimal implementation**
  * `Generator.java`에서 Mustache 모델 맵에 넘겨줄 JSON payload에 `jsonEscape` 처리를 추가합니다.
  * `test-class.mustache` 템플릿 내에 API 호출(given 블록) 전 `kafkaHelper.subscribe`를 호출하고, API 호출 완료 후 `{ ... }` 스코프 블록을 열어 `consumeNextRecord`와 `JSONAssert`를 통해 검증하는 코드 생성 템플릿 로직을 추가합니다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :test-generator:test --tests io.graphrag.generator.GeneratorTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add test-generator/
  git commit -m "feat: implement test assertion generation for captured kafka produce events"
  ```

---

### Task 7: order-service 샘플 확장 및 E2E DoD 검증 (`samples/order-service` / `e2e`)

**Files:**
- Modify: `samples/order-service/src/main/java/io/graphrag/sample/orders/OrderController.java`
- Test: Run E2E: `e2e/` 디렉토리 내의 E2E 테스트 스크립트 실행

**Interfaces:**
- Consumes: SUT HTTP POST `/api/orders`
- Produces: SUT 카프카 레코드 발행 (`order.events` 토픽)

- [ ] **Step 1: Modify SUT for Integration testing (DoD)**
  `OrderController.java`에서 주문 생성(`POST /api/orders`) 시 기존 로직에 카프카로 메시지를 발행하는 `KafkaTemplate` 로직을 임의 추가합니다. (테스트 목적의 로직 기입)
  ```java
  // KafkaTemplate을 주입받아 order-events 토픽으로 주문 완료 JSON 이벤트를 발행하는 코드 작성
  ```
- [ ] **Step 2: Run full build pipeline and verify generated test PASS**
  빌더 실행하여 새로운 `GraphAsset` 캡처 후 테스트 클래스 자동 합성 -> 생성된 E2E JUnit 테스트 클래스를 가동합니다.
  Expected: Generated test compiles successfully and PASS (Green)

- [ ] **Step 3: Commit**
  ```bash
  git add samples/order-service/
  git commit -m "test: modify order-service sample to emit kafka message and verify full pipeline"
  ```
