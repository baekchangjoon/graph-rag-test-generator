# graph.json Reuse & Pruning Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a feedback loop pruning mechanism for `graph.json` by implementing `GraphAssetSubsetter` and `GraphAssetValidator` to cascade-delete unneeded exploration paths and their associated database/HTTP mock entities, maintaining absolute referential integrity.

**Architecture:**
- Create `GraphAssetSubsetter.java` in the `shared-model` module to generate a new subset of `GraphAsset` preserving only requested paths and their cascaded dependencies (SQLs, HTTP calls, Seeds, Event Emits).
- Create `GraphAssetValidator.java` in the `shared-model` module to check if there are any dangling entity IDs (e.g. paths referencing non-existent SQL captures) in a `GraphAsset`.
- Wire the pruning functionality into the builder CLI via a new command or flag `--prune-paths <ids>`.

**Tech Stack:** Java, Jackson JSON, JUnit 5

## Global Constraints
- All file links must use the `file://` schema with absolute paths.
- Do not use placeholders such as "TODO", "implement later", or "add error handling". All instructions must show exact classes, methods, and parameters.
- Rebase-only merge must be followed (`gh pr merge --rebase`).

---

## Detailed Tasks

### Task 1: Implement GraphAssetSubsetter in shared-model
**Files:**
- Create: [GraphAssetSubsetter.java](file:///root/graph-rag-test-generator/shared-model/src/main/java/io/graphrag/model/GraphAssetSubsetter.java)

**Interfaces:**
- Consumes: `GraphAsset original`, `Collection<String> keepPathIds`.
- Produces: A new `GraphAsset` containing only the kept paths and cascade-retained SQL/HTTP/Seed/Event objects.

- [ ] **Step 1: Write the failing unit test**
  Create `GraphAssetSubsetterTest.java` in the `shared-model` test folder. Write a test case that creates a mockup `GraphAsset` with multiple paths referencing various seeds/SQLs, subsets it with one path ID, and asserts only the referenced items are kept.
  
- [ ] **Step 2: Implement subsetting logic**
  Write `GraphAssetSubsetter.java`:
  ```java
  package io.graphrag.model;

  import java.util.*;
  import java.util.stream.Collectors;

  public final class GraphAssetSubsetter {
      public static GraphAsset subset(GraphAsset original, Collection<String> keepPathIds) {
          Set<String> targetPathIds = new HashSet<>(keepPathIds);
          List<ExploredPath> paths = original.paths().stream()
              .filter(p -> targetPathIds.contains(p.id()))
              .collect(Collectors.toList());

          Set<String> keepSqlIds = paths.stream()
              .flatMap(p -> p.capturedSqlIds().stream())
              .collect(Collectors.toSet());
          Set<String> keepHttpIds = paths.stream()
              .flatMap(p -> p.capturedHttpCallIds().stream())
              .collect(Collectors.toSet());
          Set<String> keepSeedIds = paths.stream()
              .flatMap(p -> p.requiredSeedIds().stream())
              .collect(Collectors.toSet());
          Set<String> keepEmitIds = paths.stream()
              .flatMap(p -> p.capturedEventEmitIds().stream())
              .collect(Collectors.toSet());

          List<CapturedSql> sqls = original.sqls().stream()
              .filter(s -> keepSqlIds.contains(s.id()))
              .collect(Collectors.toList());
          List<CapturedHttpCall> httpCalls = original.httpCalls().stream()
              .filter(h -> keepHttpIds.contains(h.id()))
              .collect(Collectors.toList());
          List<RequiredSeed> seeds = original.seeds().stream()
              .filter(s -> keepSeedIds.contains(s.id()))
              .collect(Collectors.toList());
          List<CapturedEventEmit> eventEmits = original.capturedEventEmits().stream()
              .filter(e -> keepEmitIds.contains(e.id()))
              .collect(Collectors.toList());

          // Retain endpoints that have at least one kept path
          Set<String> activeEndpointIds = paths.stream()
              .map(ExploredPath::endpointId)
              .collect(Collectors.toSet());
          List<Endpoint> endpoints = original.endpoints().stream()
              .filter(e -> activeEndpointIds.contains(e.id()))
              .collect(Collectors.toList());

          return new GraphAsset(
              original.version(),
              endpoints,
              paths,
              sqls,
              httpCalls,
              seeds,
              eventEmits,
              original.errorContractStatusField(),
              original.errorDetailField(),
              original.errorDetailContains()
          );
      }
  }
  ```

- [ ] **Step 3: Run the tests**
  Command: `./gradlew :shared-model:test`
  Expected: PASS

- [ ] **Step 4: Commit**
  Command: `git add . && git commit -m "feat: implement GraphAssetSubsetter to cascade prune paths"`

---

### Task 2: Implement GraphAssetValidator in shared-model
**Files:**
- Create: [GraphAssetValidator.java](file:///root/graph-rag-test-generator/shared-model/src/main/java/io/graphrag/model/GraphAssetValidator.java)

**Interfaces:**
- Consumes: `GraphAsset asset`.
- Produces: Throws `IllegalStateException` detailing the dangling ID if integrity check fails.

- [ ] **Step 1: Write validation test cases**
  Add test cases in `GraphAssetSubsetterTest.java` that construct a broken `GraphAsset` (e.g. path referring to a missing SQL ID) and assert that calling `GraphAssetValidator.validate(...)` throws an `IllegalStateException`.

- [ ] **Step 2: Implement validation logic**
  Write `GraphAssetValidator.java`:
  ```java
  package io.graphrag.model;

  import java.util.Set;
  import java.util.stream.Collectors;

  public final class GraphAssetValidator {
      public static void validate(GraphAsset asset) {
          Set<String> sqlIds = asset.sqls().stream().map(CapturedSql::id).collect(Collectors.toSet());
          Set<String> httpIds = asset.httpCalls().stream().map(CapturedHttpCall::id).collect(Collectors.toSet());
          Set<String> seedIds = asset.seeds().stream().map(RequiredSeed::id).collect(Collectors.toSet());
          Set<String> emitIds = asset.capturedEventEmits().stream().map(CapturedEventEmit::id).collect(Collectors.toSet());

          for (ExploredPath path : asset.paths()) {
              for (String id : path.capturedSqlIds()) {
                  if (!sqlIds.contains(id)) {
                      throw new IllegalStateException("Dangling SQL ID " + id + " in path " + path.id());
                  }
              }
              for (String id : path.capturedHttpCallIds()) {
                  if (!httpIds.contains(id)) {
                      throw new IllegalStateException("Dangling HTTP Call ID " + id + " in path " + path.id());
                  }
              }
              for (String id : path.requiredSeedIds()) {
                  if (!seedIds.contains(id)) {
                      throw new IllegalStateException("Dangling Seed ID " + id + " in path " + path.id());
                  }
              }
              for (String id : path.capturedEventEmitIds()) {
                  if (!emitIds.contains(id)) {
                      throw new IllegalStateException("Dangling Event Emit ID " + id + " in path " + path.id());
                  }
              }
          }
      }
  }
  ```

- [ ] **Step 3: Verify tests**
  Command: `./gradlew :shared-model:test`
  Expected: PASS

- [ ] **Step 4: Commit**
  Command: `git commit -am "feat: implement GraphAssetValidator to verify referential integrity"`

---

### Task 3: Integrate Subsetter and Validator into Builder CLI
**Files:**
- Modify: [BuilderCli.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java)

**Interfaces:**
- Consumes: Command-line option `--prune-paths <comma-separated-path-ids>`.
- Produces: Updates the `graph.json` asset inside output directories with the pruned subset.

- [ ] **Step 1: Add option --prune-paths**
  Map `--prune-paths` option in the CLI argument processing logic.
  
- [ ] **Step 2: Add execution logic**
  If `--prune-paths` is provided:
  1. Load the existing `graph.json` from the output directory.
  2. Parse the target path IDs (split by comma).
  3. Call `GraphAssetSubsetter.subset(original, keepPathIds)`.
  4. Call `GraphAssetValidator.validate(subset)`.
  5. Write the subset back to `graph.json`.

- [ ] **Step 3: Verify CLI pruning integration**
  Write an integration test to verify the CLI prune command.
  Command: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.cli.BuilderIntegrationTest"`
  Expected: PASS

- [ ] **Step 4: Commit integration changes**
  Command: `git commit -am "feat: integrate --prune-paths option in BuilderCli"`
