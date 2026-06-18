# Kafka Capture Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire KafkaCaptureReceiver with EndpointExplorationRunner to capture outbound events and save them in the generated path results and GraphAsset.

**Architecture:** Inject KafkaCaptureReceiver into EndpointExplorationRunner. During HTTP request invocation inside doSend(...), drain Kafka records using the traceId. Wrap the captured records into CapturedEventEmit and propagate them to ExploredPath and GraphAsset. Manage KafkaCaptureReceiver's lifecycle in BuilderCli.

**Tech Stack:** Java 17, Gradle, JUnit 5, Apache Kafka

## Global Constraints
- Follow the TDD workflow (failing test -> code change -> pass test).
- Save results to the specified report file: `/Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/.sdd/task-4-report.md`.
- Target commit message: "feat: wire KafkaCaptureReceiver with EndpointExplorationRunner to capture outbound events".

---

### Task 1: Modify InvocationOutcome to include capturedEventEmits

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InvocationOutcome.java`

**Interfaces:**
- Produces: `InvocationOutcome` containing `List<CapturedEventEmit> capturedEventEmits`

- [ ] **Step 1: Write/Update the compact constructor and constructors of InvocationOutcome**
  Modify `InvocationOutcome.java` to:
  1. Add `private final List<CapturedEventEmit> capturedEventEmits;` (which is part of the record header).
  2. Update compact constructor to initialize it (with null normalization to `List.of()`).
  3. Adjust other constructors to pass `List.of()` as default for `capturedEventEmits`.

- [ ] **Step 2: Commit**

---

### Task 2: Inject KafkaCaptureReceiver and update doSend in EndpointExplorationRunner

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`

**Interfaces:**
- Consumes: `KafkaCaptureReceiver` via constructor injection
- Produces: `InvocationOutcome` with captured events inside `doSend`

- [ ] **Step 1: Update EndpointExplorationRunner constructors**
  Add `KafkaCaptureReceiver kafkaCapture` parameter to the constructor and store it in a field.
  Update any other constructors or builder calls that instantiate `EndpointExplorationRunner`.

- [ ] **Step 2: Drain CapturedRecord and map to CapturedEventEmit in doSend**
  Inside `doSend(...)` (after `http.send(...)`), call `kafkaCapture.drain(traceId, timeoutMillis)`.
  How to get `traceId`? `sqlScope.requestHeaders().get("traceparent")` or parse it. In fact, `sqlScope` has a traceparent if we're using OTEL. We can extract it. Let's see how `OtelSpanCapture` generates `traceparent`.
  Wait, `sqlScope` has a traceparent if we're using OTEL. We can extract traceId from it.
  Actually, let's write a helper method to extract the traceId from the traceparent header.
  We know that `TraceParent` header is `00-{traceId}-{spanId}-{traceFlags}`. So splitting by `-` and taking index 1 will give the `traceId`.
  Wait, `KafkaCaptureReceiver.getTraceIdFromHeaders` already does this!
  ```java
  String tp = headers.get("traceparent");
  if (tp != null) {
      String[] parts = tp.split("-");
      if (parts.length >= 2) {
          String candidate = parts[1];
          if (candidate.length() == 32) {
              return candidate;
          }
      }
  }
  ```
  So we can write a similar private helper in `EndpointExplorationRunner` or invoke a helper.
  After draining, map each `KafkaCaptureReceiver.CapturedRecord` to `CapturedEventEmit`:
  - `id`: `UUID.randomUUID().toString()`
  - `pathId`: (since we don't have pathId inside `doSend` yet, we can set it to null or a temporary value, or we can pass `pathId` into `doSend`. Wait! The instruction says: "Map these `CapturedEventEmit` objects using the details from `KafkaCaptureReceiver.CapturedRecord` (topic, key, payload, id, pathId)."
  If we need to set `pathId`, we can either:
  a) Pass `pathId` to `doSend` as a parameter. But `httpInvoker` is an `EndpointInvoker` which takes `input` (JsonNode) and returns `InvocationOutcome`. It doesn't receive `pathId` from `orchestrator` because `pathId` is generated inside `buildPaths` or by the orchestrator after exploration.
  Wait, the orchestrator executes:
  `ExplorationOutcome outcome = orchestrator.explore(target);`
  And the path Candidates inside `outcome.paths()` have `pathId()`.
  Wait, if `doSend` does not know `pathId` at execution time, how can it set `pathId` in the `CapturedEventEmit`?
  Maybe `doSend` sets it to null or a placeholder, and then inside `buildPaths` (where we construct the final `ExploredPath` with `candidate.pathId()`), we update the `CapturedEventEmit`s to have the correct `pathId`!
  Let's verify if `PathCandidate` or `InvocationOutcome` has the captured events.
  If `InvocationOutcome` has `capturedEventEmits`, and `PathCandidate` holds the `InvocationOutcome` (or its list of outcomes), then inside `buildPaths` we can iterate through the `CapturedEventEmit`s and rebuild them with the actual `candidate.pathId()`.
  This is extremely clean and avoids changing the `EndpointInvoker` interface!

- [ ] **Step 3: Commit**

---

### Task 3: Propagate CapturedEventEmit to ExploredPath and GraphAsset

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`

- [ ] **Step 1: Propagate in buildPaths**
  In `buildPaths` of `EndpointExplorationRunner`, extract `capturedEventEmits` from the `outcome`/`candidate`.
  Update their `pathId` to `candidate.pathId()`.
  Add their `id`s to the `capturedEventEmitIds` list of `ExploredPath`.
  Collect all `CapturedEventEmit`s from all paths and return them or store them in `PathsBundle`/`EndpointResult`.
  Wait, `EndpointResult` needs to propagate these emits to `BuilderCli` so they can be added to `GraphAsset`.
  Let's modify `EndpointResult` or `PathsBundle` to hold the collected `CapturedEventEmit`s.
  Wait, `EndpointResult` constructor:
  ```java
  public record EndpointResult(List<ExploredPath> paths, List<CapturedSql> sql,
                                List<CapturedHttpCall> httpCalls, List<RequiredSeed> seeds,
                                ExplorationReport.EndpointExploration report,
                                ExecutionDataStore cumulativeCoverage)
  ```
  We should modify it (or its constructors) to also include `List<CapturedEventEmit> capturedEventEmits`.

- [ ] **Step 2: Propagate in BuilderCli**
  In `BuilderCli.java`, collect `capturedEventEmits` from `EndpointResult` and add them to `acc.capturedEventEmits()` (wait, does `ExplorationAccumulators` have `capturedEventEmits`? Let's check `ExplorationAccumulators` record header. Ah! In `BuilderCli.java`, line 286:
  `ExplorationAccumulators acc = new ExplorationAccumulators(paths, sql, httpCalls, wsExchanges, kafkaExchanges, allSeeds, reportEntries, coveredAppBranches, runWideExec);`
  Wait! `ExplorationAccumulators` has `kafkaExchanges` but does it have `capturedEventEmits`?
  Let's check `ExplorationAccumulators` fields. Line 351 of `BuilderCli.java` was truncated.
  Let's query the `ExplorationAccumulators` record definition.
  If it doesn't have `capturedEventEmits`, we should add it.
  And at the end, instantiate `GraphAsset` with these `capturedEventEmits`.

- [ ] **Step 3: Commit**

---

### Task 4: Manage KafkaCaptureReceiver Lifecycle in BuilderCli

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`

- [ ] **Step 1: Instantiate and start KafkaCaptureReceiver**
  In `BuilderCli.explore(...)`, if `config.withKafka()` is true and `kafkaBootstrap` is not null, instantiate `KafkaCaptureReceiver` and start it.
  Pass it to the `EndpointExplorationRunner` constructor.
  Wait, if `withKafka` is false or `kafkaBootstrap` is null, we can pass a dummy/noop or null?
  If we allow `kafkaCapture` to be null, we must guard against null pointer exceptions in `EndpointExplorationRunner`.
  Wrap the execution in try-with-resources or try-finally to ensure `KafkaCaptureReceiver.close()` is called.

- [ ] **Step 2: Commit**

---

### Task 5: Add Integration Test in OtelHttpCaptureAcceptanceTest

**Files:**
- Modify: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/OtelHttpCaptureAcceptanceTest.java`

- [ ] **Step 1: Write the failing test**
  Add a test `httpRequest_capturesOutboundKafkaEvent` that starts a SUT with Kafka enabled (wait, does the integration test environment support Kafka? `DbConfig` has `withKafka` support in `AnalysisEnvironment`. Let's check if `AnalysisEnvironment` starts Testcontainers Kafka or similar).
  Wait, we should inspect `AnalysisEnvironment.java` to see how it starts Kafka.
  Write a test case where we send an HTTP request to SUT (e.g. POST `/api/orders` which emits an order event to Kafka, if SUT indeed does that. Let's verify what `order-service` does on `POST /api/orders` or other endpoints!).

- [ ] **Step 2: Run test to verify it fails**
  `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.capture.OtelHttpCaptureAcceptanceTest`
  Expected: FAIL

- [ ] **Step 3: Ensure test passes**
  Implement all modifications and verify the test passes.

- [ ] **Step 4: Commit**
