# Task 3 Fix Report

## Overview
This report documents the fixes applied to resolve the Important and Minor issues identified in the Task 3 Review for `KafkaCaptureReceiver.java`.

## Changes Implemented

### 1. Wait-Notify Loop Optimization
- **Issue:** The `drain()` method was using polling with `Thread.sleep(50)` to wait for new records, which is inefficient.
- **Resolution:** Updated the synchronization mechanism as follows:
  - Inside `addRecord`, if a record is successfully offered to the queue, `queue.notifyAll()` is called to wake up any threads waiting in `drain`.
  - Inside `drain`, if the requested trace record is not yet found and the deadline has not expired, the thread releases the lock and waits on the `queue` object using `queue.wait(remainingMillis)` instead of sleeping.
  - The remaining timeout (`remainingMillis`) is dynamically recalculated on each iteration.

### 2. AdminClient listTopics() Timeout Added
- **Issue:** The call `admin.listTopics().names().get()` could block indefinitely under network/Kafka broker issues.
- **Resolution:** Enforced a 5-second timeout by replacing it with `names().get(5, java.util.concurrent.TimeUnit.SECONDS)`.

### 3. Case-Insensitive Traceparent Header Lookup
- **Issue:** Traceparent header matching was case-sensitive.
- **Resolution:** Replaced the simple `headers.get("traceparent")` with a case-insensitive iteration over the header map.
- **Testing:** Added a new test `testCapturesAndDrainsRecordByTraceIdCaseInsensitive` in `KafkaCaptureReceiverTest.java` that uses a mixed-case header `TraceParent` to verify that the implementation successfully captures and drains the trace record case-insensitively.

### 4. Simple int queueSize under Lock
- **Issue:** The field `AtomicInteger queueSize` was used alongside explicit `synchronized (queue)` synchronization, which is redundant.
- **Resolution:** Replaced `AtomicInteger queueSize` with a plain `int queueSize`. All accesses and mutations (increments, decrements, reads) to `queueSize` are fully synchronized under the `queue` lock.

## Verification Results
- Ran `./gradlew :graph-rag-builder:cleanTest :graph-rag-builder:test --tests io.graphrag.builder.run.KafkaCaptureReceiverTest --no-build-cache`
- Status: **PASSED**
