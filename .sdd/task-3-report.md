# Task 3 Completion Report: KafkaCaptureReceiver

## 1. Created/Modified Files

### Created Files
- [KafkaCaptureReceiver.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureReceiver.java)
  - Manages background thread to consume messages from Kafka broker.
  - Filters out internal topics (e.g. `_` prefix) using regex pattern: `^(?!_).+`.
  - Parses W3C `traceparent` header to extract the 32-character hexadecimal `traceId` to support correlation.
  - Buffers up to 10,000 maximum captured records in a thread-safe capped queue, evicting the oldest elements when full.
  - Defensively wraps null tombstones and non-JSON string payloads in Jackson `NullNode`/`TextNode` respectively.
  - Implements thread-safe `drain()` with waiting logic to poll matched messages for specific `traceId`s.

- [KafkaCaptureReceiverTest.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/test/java/io/graphrag/builder/run/KafkaCaptureReceiverTest.java)
  - ImplementsJUnit 5 integration tests using Testcontainers `KafkaContainer` (utilizing standard `confluentinc/cp-kafka:7.4.0` image).
  - Tests verify:
    1. Base end-to-end trace correlation and message capture.
    2. Dynamic exclusion of internal topics (e.g., `__internal-topic`).
    3. Capped queue size eviction (sending 10,005 items and validating that the oldest 5 were correctly evicted while the newest 5 remain).
    4. Defensive fallback for null tombstones and non-JSON payloads.

---

## 2. Test Commands Executed

Run Gradle test command:
```bash
./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.KafkaCaptureReceiverTest
```

---

## 3. Test Console Output

```text
> Task :testlib:processResources UP-TO-DATE
> Task :graph-rag-builder:processResources UP-TO-DATE
> Task :graph-rag-builder:processTestResources UP-TO-DATE
> Task :shared-model:compileJava UP-TO-DATE
> Task :shared-model:processResources NO-SOURCE
> Task :shared-model:classes UP-TO-DATE
> Task :shared-model:jar UP-TO-DATE
> Task :samples:order-service:compileJava UP-TO-DATE
> Task :samples:order-service:processResources UP-TO-DATE
> Task :samples:order-service:classes UP-TO-DATE
> Task :samples:order-service:resolveMainClassName UP-TO-DATE
> Task :testlib:compileJava UP-TO-DATE
> Task :testlib:classes UP-TO-DATE
> Task :testlib:jar UP-TO-DATE
> Task :graph-rag-builder:compileJava UP-TO-DATE
> Task :graph-rag-builder:classes UP-TO-DATE
> Task :samples:order-service:bootJar UP-TO-DATE

> Task :graph-rag-builder:compileTestJava
Note: /Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/test/java/io/graphrag/builder/run/KafkaCaptureReceiverTest.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.

> Task :graph-rag-builder:testClasses
> Task :graph-rag-builder:test

[Incubating] Problems report is available at: file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 15s
14 actionable tasks: 2 executed, 12 up-to-date
```
- Total test cases run: 4
- Passed: 4
- Failed: 0
