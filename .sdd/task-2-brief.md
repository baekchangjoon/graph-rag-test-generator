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

