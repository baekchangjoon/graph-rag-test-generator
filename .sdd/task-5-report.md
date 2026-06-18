# Task 5 Report: Extend KafkaHelper with async pre-subscribe and add jsonassert dependency

## Modified and Created Files

1. **`testlib/build.gradle.kts`**
   - Added `api("org.skyscreamer:jsonassert:1.5.1")` to the dependencies block.
   - Added `testImplementation(libs.testcontainers.kafka)` to run real integration tests using Testcontainers Kafka.

2. **`testlib/src/main/java/io/graphrag/testlib/api/KafkaHelper.java`**
   - Extended class to support asynchronous subscription and consumption on a background thread.
   - Added `public synchronized void subscribe(String topic)`: Spawns a dedicated background consumer thread (`ConsumerRunner`) with a unique random `group.id` and `auto.offset.reset = latest`.
   - Added `public ConsumerRecord<String, String> consumeNextRecord(String topic, Duration timeout)`: Waits for the next buffered record from the thread-safe `LinkedBlockingQueue` and returns it. If it times out, returns `null`.
   - Modified `close()` to cleanly shut down all background threads and consumers by invoking `consumer.wakeup()` and joining them, then closing the producer.
   - Implemented thread-safe queue buffering (`ConcurrentHashMap` of `LinkedBlockingQueue`) for isolation and concurrent safety.

3. **`testlib/src/test/java/io/graphrag/testlib/api/KafkaHelperTest.java` (New File)**
   - Wrote unit/integration tests using Testcontainers Kafka to verify async `subscribe` and `consumeNextRecord` functionality.
   - Verified that JSONAssert works correctly on the consumed message.

---

## TDD Step-by-Step Execution

### Step 1 & 2: Write failing test & run to verify compilation failure
- Created the test class `KafkaHelperTest.java` with calls to non-existent `subscribe` and `consumeNextRecord` methods, and missing `jsonassert` dependency.
- Ran `./gradlew :testlib:test` and verified it failed with 9 compilation errors:
  ```
  /Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/testlib/src/test/java/io/graphrag/testlib/api/KafkaHelperTest.java:9: error: package org.skyscreamer.jsonassert does not exist
  import org.skyscreamer.jsonassert.JSONAssert;
                                   ^
  /Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/testlib/src/test/java/io/graphrag/testlib/api/KafkaHelperTest.java:10: error: package org.testcontainers.containers does not exist
  import org.testcontainers.containers.KafkaContainer;
  ...
  ```

### Step 3: Write minimal implementation
- Added `jsonassert` and `testcontainers-kafka` dependencies to `testlib/build.gradle.kts`.
- Implemented `subscribe`, `consumeNextRecord`, `close`, and thread-safe buffering in `KafkaHelper.java`.

### Step 4: Run test to verify it passes
- Executed `./gradlew :testlib:test`.
- The tests successfully compiled and passed on a real Testcontainers Kafka broker instance.
- Avoided `InterruptedException` during cleanup by relying on `consumer.wakeup()` without interrupting the thread during `consumer.close()`.

---

## Test Results and Console Output

The test suite ran successfully. Here is the test execution output from the JUnit XML result file:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="io.graphrag.testlib.api.KafkaHelperTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-06-18T08:03:26.327Z" hostname="changjoacStudio.Davolink" time="1.934">
  <properties/>
  <testcase name="testSubscribeAndConsumeNextRecord()" classname="io.graphrag.testlib.api.KafkaHelperTest" time="1.934"/>
  <system-out><![CDATA[]]></system-out>
  <system-err><![CDATA[[Test worker] INFO org.testcontainers.images.PullPolicy - Image pull policy will be performed by: DefaultPullPolicy()
[Test worker] INFO org.testcontainers.utility.ImageNameSubstitutor - Image name substitution will be performed by: DefaultImageNameSubstitutor (composite of 'ConfigurationFileImageNameSubstitutor' and 'PrefixingImageNameSubstitutor')
[Test worker] INFO org.testcontainers.DockerClientFactory - Testcontainers version: 1.21.3
[Test worker] INFO org.testcontainers.dockerclient.DockerClientProviderStrategy - Loaded org.testcontainers.dockerclient.UnixSocketClientProviderStrategy from ~/.testcontainers.properties, will try it first
[Test worker] INFO org.testcontainers.dockerclient.DockerClientProviderStrategy - Found Docker environment with local Unix socket (unix:///var/run/docker.sock)
[Test worker] INFO org.testcontainers.DockerClientFactory - Docker host IP address is localhost
[Test worker] INFO org.testcontainers.DockerClientFactory - Connected to docker: 
  Server Version: 29.5.3
  API Version: 1.54
  Operating System: Docker Desktop
  Total Memory: 7836 MB
  Labels: 
    com.docker.desktop.address=unix:///Users/changjoonbaek/Library/Containers/com.docker.docker/Data/docker-cli.sock
[Test worker] INFO tc.testcontainers/ryuk:0.12.0 - Creating container for image: testcontainers/ryuk:0.12.0
[Test worker] INFO org.testcontainers.utility.RegistryAuthLocator - Credential helper/store (docker-credential-desktop) does not have credentials for https://index.docker.io/v1/
[Test worker] INFO tc.testcontainers/ryuk:0.12.0 - Container testcontainers/ryuk:0.12.0 is starting: f5def6fb1edaa7c49a2605c481aa8d4c4bb150c2d8823ef81cba4aebcf2990f1
[Test worker] INFO tc.testcontainers/ryuk:0.12.0 - Container testcontainers/ryuk:0.12.0 started in PT0.304178S
[Test worker] INFO org.testcontainers.utility.RyukResourceReaper - Ryuk started - will monitor and terminate Testcontainers containers on JVM exit
[Test worker] INFO org.testcontainers.DockerClientFactory - Checking the system...
[Test worker] INFO org.testcontainers.DockerClientFactory - ✔︎ Docker server version should be at least 1.6.0
[Test worker] INFO tc.confluentinc/cp-kafka:7.4.0 - Creating container for image: confluentinc/cp-kafka:7.4.0
[Test worker] INFO tc.confluentinc/cp-kafka:7.4.0 - Container confluentinc/cp-kafka:7.4.0 is starting: 8bb6c6321891a11a18e51711b81d2d724cbe9e44be890f19a93551acd9af0b04
[Test worker] INFO tc.confluentinc/cp-kafka:7.4.0 - Container confluentinc/cp-kafka:7.4.0 started in PT2.551755S
...
[kafka-helper-consumer-test-topic-112a4335-0145-4f17-bb95-06b67997290b-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] INFO org.apache.kafka.clients.consumer.internals.LegacyKafkaConsumer - [Consumer clientId=consumer-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8-1, groupId=test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] Subscribed to topic(s): test-topic-112a4335-0145-4f17-bb95-06b67997290b
[kafka-producer-network-thread | producer-1] INFO org.apache.kafka.clients.Metadata - [Producer clientId=producer-1] Cluster ID: PpyEMH4yQH2qNdcmsHLZbw
[kafka-producer-network-thread | producer-1] INFO org.apache.kafka.clients.producer.internals.TransactionManager - [Producer clientId=producer-1] ProducerId set to 0 with epoch 0
[kafka-helper-consumer-test-topic-112a4335-0145-4f17-bb95-06b67997290b-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] WARN org.apache.kafka.clients.NetworkClient - [Consumer clientId=consumer-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8-1, groupId=test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] Error while fetching metadata with correlation id 2 : {test-topic-112a4335-0145-4f17-bb95-06b67997290b=LEADER_NOT_AVAILABLE}
[kafka-helper-consumer-test-topic-112a4335-0145-4f17-bb95-06b67997290b-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] INFO org.apache.kafka.clients.Metadata - [Consumer clientId=consumer-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8-1, groupId=test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] Cluster ID: PpyEMH4yQH2qNdcmsHLZbw
[kafka-helper-consumer-test-topic-112a4335-0145-4f17-bb95-06b67997290b-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] INFO org.apache.kafka.clients.consumer.internals.ConsumerCoordinator - [Consumer clientId=consumer-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8-1, groupId=test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] Discovered group coordinator localhost:65364 (id: 2147483646 rack: null)
[kafka-helper-consumer-test-topic-112a4335-0145-4f17-bb95-06b67997290b-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] INFO org.apache.kafka.clients.consumer.internals.ConsumerCoordinator - [Consumer clientId=consumer-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8-1, groupId=test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] (Re-)joining group
...
[kafka-helper-consumer-test-topic-112a4335-0145-4f17-bb95-06b67997290b-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] INFO org.apache.kafka.clients.consumer.internals.ConsumerRebalanceListenerInvoker - [Consumer clientId=consumer-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8-1, groupId=test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] Revoke previously assigned partitions test-topic-112a4335-0145-4f17-bb95-06b67997290b-0
[kafka-helper-consumer-test-topic-112a4335-0145-4f17-bb95-06b67997290b-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] INFO org.apache.kafka.clients.consumer.internals.ConsumerCoordinator - [Consumer clientId=consumer-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8-1, groupId=test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8] Member consumer-test-group-efb772a0-1ba2-4795-95ef-0abb8656baa8-1-81829bd9-888f-475c-b46f-99393086bad4 sending LeaveGroup request to coordinator localhost:65364 (id: 2147483646 rack: null) due to the consumer is being closed
...
[Test worker] INFO org.apache.kafka.clients.producer.KafkaProducer - [Producer clientId=producer-1] Closing the Kafka producer with timeoutMillis = 9223372036854775807 ms.
[Test worker] INFO org.apache.kafka.common.metrics.Metrics - Metrics scheduler closed
[Test worker] INFO org.apache.kafka.common.metrics.Metrics - Closing reporter org.apache.kafka.common.metrics.JmxReporter
[Test worker] INFO org.apache.kafka.common.metrics.Metrics - Closing reporter org.apache.kafka.common.telemetry.internals.ClientTelemetryReporter
[Test worker] INFO org.apache.kafka.common.metrics.Metrics - Metrics reporters closed
[Test worker] INFO org.apache.kafka.common.utils.AppInfoParser - App info kafka.producer for producer-1 unregistered
]]></system-err>
</testsuite>
```
