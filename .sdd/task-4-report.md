# Task 4 Report: EndpointExplorationRunner 및 캡처 파이프라인 연동

본 보고서는 `KafkaCaptureReceiver`와 `EndpointExplorationRunner`를 연동하여 SUT의 아웃바운드 Kafka 이벤트를 성공적으로 수집하고 이를 탐색 결과(Path 및 GraphAsset)에 저장하도록 구현한 내역을 상세히 기록합니다.

## 1. 수정/생성된 파일 목록
- **SUT (samples/order-service)**
  - [OrderController.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/samples/order-service/src/main/java/io/graphrag/sample/orders/OrderController.java): `KafkaTemplate`을 의존성 주입받아 주문 생성 시 `order.events` 토픽으로 이벤트를 발행하는 기능 추가.
- **shared-model**
  - [InvocationOutcome.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/main/java/io/graphrag/builder/explore/InvocationOutcome.java): `capturedEventEmits` 리스트 필드를 추가하고, 생성자와 오버로드된 생성자들을 업데이트.
- **graph-rag-builder**
  - [PathCandidate.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/main/java/io/graphrag/builder/explore/PathCandidate.java): `capturedEventEmits` 리스트 필드를 추가하고, 생성자와 오버로드된 생성자들을 업데이트.
  - [ExplorationOrchestrator.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/main/java/io/graphrag/builder/explore/ExplorationOrchestrator.java): `toOutcome` 단계에서 `PathCandidate` 생성 시 `InvocationOutcome`의 `capturedEventEmits`를 넘겨주도록 수정.
  - [EndpointExplorationRunner.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java):
    - `EndpointResult`와 `PathsBundle` 레코드 헤더에 `capturedEventEmits` 추가.
    - `KafkaCaptureReceiver`를 생성자 주입받고 `doSend(...)` 수행 후 `kafkaCapture.drain(...)`을 통해 traceId별 Kafka 레코드를 회수하여 `CapturedEventEmit`으로 매핑.
    - `buildPaths` 내에서 캡처된 이벤트를 deterministic ID (`event-${pathId}-${seq}`) 형태로 재구성해 `ExploredPath`에 누적 및 `PathsBundle`에 적재.
  - [BuilderCli.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java):
    - `ExplorationAccumulators` 에 `capturedEventEmits` 필드 추가.
    - `build(...)` 내에서 `plan.carriedEventEmits()`를 누적 목록에 병합.
    - `explore(...)` 내에서 `KafkaCaptureReceiver` 객체를 기동하고 start/close 수명주기를 try-with-resources로 안전하게 관리하며, `EndpointExplorationRunner` 생성 시 주입.
    - 최종 `GraphAsset` 생성 시 `capturedEventEmits`를 인자로 전달.
  - [OtelHttpCaptureAcceptanceTest.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/graph-rag-builder/src/test/java/io/graphrag/builder/capture/OtelHttpCaptureAcceptanceTest.java):
    - Kafka 브로커가 활성화된 `AnalysisEnvironment`를 구동하고, `KafkaCaptureReceiver` 및 `EndpointExplorationRunner` 연동 하에 주문을 생성하는 HTTP API 호출을 유발하여 아웃바운드 Kafka 이벤트가 성공적으로 캡처되는지 검증하는 `httpRequest_capturesOutboundKafkaEvent` 통합 테스트 추가.

## 2. 작성된 테스트 내용 및 실행 결과
추가된 통합 테스트 `httpRequest_capturesOutboundKafkaEvent`는 JaCoCo 덤프를 우회하기 위해 `CoverageClient`를 익명 서브클래스화하여 mock 동작을 얹고, 실제 Kafka 토픽(`order.events`)으로 발행된 이벤트를 캡처하여 검증에 성공하였습니다.

### 테스트 실행 명령어
```bash
./gradlew :graph-rag-builder:test --tests io.graphrag.builder.capture.OtelHttpCaptureAcceptanceTest
```

### 테스트 실행 콘솔 출력
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
> Task :graph-rag-builder:testClasses
> Task :graph-rag-builder:test

BUILD SUCCESSFUL in 21s
14 actionable tasks: 2 executed, 12 up-to-date
```
