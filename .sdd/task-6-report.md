# Task 6 Report: Test Generator & Mustache Template Expansion for Kafka Outbound Assertions

## Modified Files

1. **`test-generator/src/main/java/io/graphrag/generator/Generator.java`**
   - Modified `generateSingle` to fetch `capturedEventEmitsForPath(pathId)` from the client.
   - If captured events are present, passed `hasKafkaEmits` and a structured list `kafkaEmits` (containing `topic`, JSON-escaped `key`, and JSON-escaped `payloadJson`) to the Mustache template scope.

2. **`test-generator/src/main/resources/templates/test-class.mustache`**
   - Added `scope.kafka().subscribe("{{{topic}}}");` before the API call starts for each captured Kafka event.
   - Added a scoped block `{ ... }` after the API call assertions to wait and assert each captured event using `consumeNextRecord` and `JSONAssert.assertEquals`.

3. **`test-generator/src/test/java/io/graphrag/generator/GeneratorTest.java`**
   - Added `generate_withKafkaOutboundEvents_includesAssertions` test case using a `FakeGraphRagClient` to verify that both topic subscription and wait-and-assert block are correctly synthesized with JSON-escaped payload and key, and that keys are omitted if not present.

4. **`test-generator/src/test/resources/golden/OrdersPostTest.java.golden`**
   - Updated the golden file to include the correct Kafka outbound assertions since the happy path in `fixture-graph` contains captured Kafka events.

---

## Tests Run & Verification Results

### 1. Verification of the failing test (TDD Step 2)
Running `./gradlew :test-generator:test --tests io.graphrag.generator.GeneratorTest` before implementation changes failed with:
```
GeneratorTest > generate_withKafkaOutboundEvents_includesAssertions() FAILED
    java.lang.AssertionError: 
    Expecting actual:
      ...
    to contain:
      "scope.kafka().subscribe("orders-topic");" 
        at io.graphrag.generator.GeneratorTest.generate_withKafkaOutboundEvents_includesAssertions(GeneratorTest.java:233)
```

### 2. Verification of the passing test (TDD Step 4)
Running `./gradlew :test-generator:test` after implementation changes and updating the golden file succeeded:
```
> Task :test-generator:processResources UP-TO-DATE
> Task :shared-model:compileJava UP-TO-DATE
> Task :shared-model:processResources NO-SOURCE
> Task :shared-model:classes UP-TO-DATE
> Task :shared-model:jar UP-TO-DATE
> Task :test-generator:processTestResources
> Task :test-generator:compileJava UP-TO-DATE
> Task :test-generator:classes UP-TO-DATE
> Task :test-generator:compileTestJava UP-TO-DATE
> Task :test-generator:testClasses
> Task :test-generator:test

BUILD SUCCESSFUL in 1s
7 actionable tasks: 2 executed, 5 up-to-date
```
All 13 tests completed successfully.
