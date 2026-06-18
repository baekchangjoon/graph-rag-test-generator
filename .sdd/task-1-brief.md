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

