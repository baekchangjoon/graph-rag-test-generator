# Real-time Incremental Graph Asset Save Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modify `graph-rag-builder` to write partition files (`partitions/<HandlerClass>.json`) incrementally as each controller exploration finishes, minimizing memory consumption and safeguarding against information loss from crashes.

**Architecture:**
- Extend the [GraphStore.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/store/GraphStore.java) interface to support atomic, partial partition saves.
- Implement incremental flushing in [PartitionedGraphStore.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/store/PartitionedGraphStore.java) to append or write to individual JSON files without overwriting the entire graph.
- Update the exploration runner flow to flush progress when a handler's endpoints are fully explored.

**Tech Stack:** Java, Jackson JSON, File I/O, JUnit 5

## Global Constraints
- All file links must use the `file://` schema with absolute paths.
- Do not use placeholders such as "TODO", "implement later", or "add error handling". All instructions must show exact classes, methods, and parameters.
- Rebase-only merge must be followed (`gh pr merge --rebase`).

---

## Detailed Tasks

### Task 1: Extend GraphStore Interface for Partial Partition Saves
**Files:**
- Modify: [GraphStore.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/store/GraphStore.java)

**Interfaces:**
- Consumes: Partition data collections (Paths, SQLs, HTTPs, Seeds, Emits).
- Produces: Persistent sub-JSON files matching the partition name.

- [ ] **Step 1: Open GraphStore.java**
  Add the method signature to the interface:
  ```java
  void savePartition(
      String partitionName,
      java.util.Collection<io.graphrag.model.ExploredPath> paths,
      java.util.Collection<io.graphrag.model.CapturedSql> sqls,
      java.util.Collection<io.graphrag.model.CapturedHttpCall> httpCalls,
      java.util.Collection<io.graphrag.model.RequiredSeed> seeds,
      java.util.Collection<io.graphrag.model.CapturedEventEmit> eventEmits
  ) throws java.io.IOException;
  ```

- [ ] **Step 2: Verify compiles**
  Command: `./gradlew :graph-rag-builder:compileJava`
  Expected: BUILD SUCCESSFUL (or failures due to unimplemented method in PartitionedGraphStore)

- [ ] **Step 3: Commit interface changes**
  Command: `git commit -am "feat: extend GraphStore interface to support savePartition method"`

---

### Task 2: Implement savePartition in PartitionedGraphStore
**Files:**
- Modify: [PartitionedGraphStore.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/store/PartitionedGraphStore.java)

**Interfaces:**
- Consumes: Specific partition datasets.
- Produces: Written `partitions/<partitionName>.json` files maintaining json schemas.

- [ ] **Step 1: Write savePartition implementation**
  In [PartitionedGraphStore.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/store/PartitionedGraphStore.java), serialize only the passed collection parameters as a partitioned sub-graph unit into `partitions/` directory. Use temporary writing roll-over strategy to maintain atomic operations:
  ```java
  @Override
  public void savePartition(String partitionName, Collection<ExploredPath> paths, Collection<CapturedSql> sqls,
                            Collection<CapturedHttpCall> httpCalls, Collection<RequiredSeed> seeds, Collection<CapturedEventEmit> eventEmits) throws IOException {
      Path file = dir.resolve("partitions/" + partitionName + ".json");
      Path tmpFile = dir.resolve("partitions/" + partitionName + ".json.tmp");
      Files.createDirectories(file.getParent());
      // Serialize content into tmpFile using ObjectMapper
      // ...
      Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
  ```

- [ ] **Step 2: Update existing global save methods**
  Ensure the full `save(GraphAsset)` method delegates internally to the `savePartition` methods to dry-up code duplication.

- [ ] **Step 3: Run existing store unit tests**
  Command: `./gradlew :graph-rag-builder:test`
  Expected: PASS

- [ ] **Step 4: Commit**
  Command: `git commit -am "feat: implement savePartition in PartitionedGraphStore with atomic rollover"`

---

### Task 3: Trigger Partition Flushes from Exploration Runner
**Files:**
- Modify: [BuilderCli.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java) (or related Runner class)

- [ ] **Step 1: Identify completion callback locations**
  Locate the loops that iterate over SUT handler/controller classes during endpoint discovery.
  
- [ ] **Step 2: Add flush callback**
  After exploring endpoints belonging to a specific handler class (e.g. `OrderWebController`), collect all paths and mock calls generated during its run, and write them using `GraphStore.savePartition(...)`.

- [ ] **Step 3: Commit runner modifications**
  Command: `git commit -am "feat: trigger savePartition in runner upon handler exploration completions"`

---

### Task 4: Add Resume Capability & Verification Integration Test
**Files:**
- Create: Test verification in `IncrementalBuildPlannerTest.java` or separate Integration test.

- [ ] **Step 1: Verify runner resume logic**
  Simulate an aborted run by executing a test case that stops mid-way, verify it wrote at least one partition JSON file, and assert that restarting the builder loads the saved partition without re-running exploration for that handler.

- [ ] **Step 2: Run verification test suite**
  Command: `./gradlew :graph-rag-builder:test`
  Expected: All checks PASS.

- [ ] **Step 3: Commit verification**
  Command: `git commit -am "test: verify incremental flush and resume functionality"`
