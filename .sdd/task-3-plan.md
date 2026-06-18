# KafkaCaptureReceiver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `KafkaCaptureReceiver` in `graph-rag-builder` with a capped queue and topic regex filtering to capture outbound SUT Kafka messages.

**Architecture:** A background thread polls Kafka records matching regex `^(?!_).+`, parses the W3C `traceparent` header to extract the 32-hex `traceId`, wraps value safely in `TextNode` if null/non-JSON, and buffers records into a 10,000-capped thread-safe queue. The HTTP runner thread drains matched records by calling `drain()`.

**Tech Stack:** Java 17, Apache Kafka Client, Jackson, Testcontainers Kafka, JUnit 5, AssertJ.

## Global Constraints
- Target files:
  - Main: `graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureReceiver.java`
  - Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/KafkaCaptureReceiverTest.java`
- TraceId parsing: Extract 32 hex chars from W3C `traceparent` header (e.g. `00-<traceId>-<spanId>-<traceFlags>`).
- Filter topics starting with `_` using regex: `^(?!_).+`.
- Capped queue size: 10,000 maximum. Discard oldest when full.
- Thread-safe queues and status management.

---

### Task 1: Write the Failing Test
**Files:**
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/run/KafkaCaptureReceiverTest.java`
- Reference: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/otlp/OtlpTraceReceiverTest.java`

- [ ] **Step 1: Write the failing test**
  Create `KafkaCaptureReceiverTest.java` with test cases:
  1. `capturesOutboundRecordsAndDrainsByTraceId`: Starts a Testcontainers `KafkaContainer`, publishes a message to a test topic with a `traceparent` header, starts the receiver, drains the record by `traceId`, and asserts that the topic, key, and parsed JSON payload match.
  2. `filtersOutInternalTopics`: Ensures internal topics starting with `_` are ignored.
  3. `capsQueueToTenThousand`: Inserts more than 10,000 records, verify oldest are evicted.
  4. `handlesNullTombstonesAndNonJsonPayloads`: Verifies that null tombstones and non-JSON strings are safely wrapped in Jackson `TextNode` (or NullNode).

  Test code draft for compilation fail (importing non-existing `KafkaCaptureReceiver`):
  ```java
  package io.graphrag.builder.run;

  import org.junit.jupiter.api.Test;
  import static org.assertj.core.api.Assertions.assertThat;

  class KafkaCaptureReceiverTest {
      @Test
      void compileErrorTest() {
          // This will fail to compile as KafkaCaptureReceiver does not exist yet
          KafkaCaptureReceiver receiver = new KafkaCaptureReceiver("localhost:9092");
          assertThat(receiver).isNotNull();
      }
  }
  ```

- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.KafkaCaptureReceiverTest`
  Expected: FAIL (Compilation error: symbol `KafkaCaptureReceiver` not found)

---

### Task 2: Implement Minimal Code
**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureReceiver.java`

- [ ] **Step 1: Write the minimal implementation**
  Create `KafkaCaptureReceiver.java` with:
  - `CapturedRecord` record to hold captured fields.
  - Thread safety using `synchronized` blocks or atomic counters.
  - `AdminClient` topic list fallback logic + regex filter.
  - Background polling thread.
  - Safely wrapping null/non-JSON records using Jackson `JsonNode`/`TextNode`.
  - `drain()` with wait and poll loop.
  - `close()` to stop background thread and close consumer.

- [ ] **Step 2: Run test to verify it passes**
  Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.KafkaCaptureReceiverTest`
  Expected: PASS

- [ ] **Step 3: Commit**
  Run command:
  ```bash
  git add graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureReceiver.java graph-rag-builder/src/test/java/io/graphrag/builder/run/KafkaCaptureReceiverTest.java
  git commit -m "feat: implement KafkaCaptureReceiver with capped queue and topic regex filtering"
  ```
