# Task 5 Fix Report

## Goal
Resolve flakiness in `KafkaHelperTest.java` due to hardcoded `Thread.sleep(1000)` and protect `KafkaHelper.java` against double-subscription.

## Implemented Changes

### 1. `testlib/src/main/java/io/graphrag/testlib/api/KafkaHelper.java`
- **Thread-safe Partition Assignment Check:**
  - Added a `private volatile boolean assigned = false;` field inside `ConsumerRunner`.
  - Updated `assigned` state inside the runner's background thread `run()` loop using `this.assigned = !consumer.assignment().isEmpty();` right after `consumer.poll(...)`.
  - Exposed `public boolean isAssigned()` in `ConsumerRunner`.
  - Exposed `public synchronized boolean isAssigned(String topic)` in `KafkaHelper` to check if any running `ConsumerRunner` for the given topic has successfully obtained partition assignments.
- **Double-Subscription Prevention:**
  - Guarded the `subscribe(String topic)` method by checking `if (buffers.containsKey(topic)) { return; }` before creating and starting a new `ConsumerRunner`.

### 2. `testlib/src/test/java/io/graphrag/testlib/api/KafkaHelperTest.java`
- **Dynamic Waiting via Awaitility:**
  - Replaced `Thread.sleep(1000)` with `org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> kafkaHelper.isAssigned(topic));`.
- **Sanity Check for Double-Subscription:**
  - Added a double-subscription call (`kafkaHelper.subscribe(topic)` twice) to verify that double-subscription protection functions without issues or throwing exceptions.

## Verification Evidence
Ran `./gradlew :testlib:cleanTest :testlib:test` successfully:
```
BUILD SUCCESSFUL in 6s
7 actionable tasks: 3 executed, 4 up-to-date
```
Tests pass cleanly without flakiness or unnecessary hardcoded delays.
