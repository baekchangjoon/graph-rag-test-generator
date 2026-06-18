# Task 1: 데이터 모델 추가 및 호환성 생성자 구현 결과 보고서

## 1. 생성 및 수정된 파일 목록

### 생성된 파일
* **[CapturedEventEmit.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/shared-model/src/main/java/io/graphrag/model/CapturedEventEmit.java)**: SUT 아웃바운드 이벤트 캡처를 지원하기 위한 신규 레코드 모델

### 수정된 파일
* **[ExploredPath.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/shared-model/src/main/java/io/graphrag/model/ExploredPath.java)**:
  * `capturedEventEmitIds` 필드 추가
  * compact 생성자에서 `capturedEventEmitIds`가 null일 경우 빈 리스트(`List.of()`)로 초기화하도록 가드 조건 추가
  * 구버전 호환을 위한 12-argument 오버로딩 생성자 구현
* **[GraphAsset.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/shared-model/src/main/java/io/graphrag/model/GraphAsset.java)**:
  * `capturedEventEmits` 필드 추가
  * compact 생성자에서 `capturedEventEmits`가 null일 경우 빈 리스트(`List.of()`)로 초기화하도록 가드 조건 추가
  * 구버전 호환을 위한 13-argument 오버로딩 생성자 구현
* **[JsonRoundTripTest.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag/shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java)**:
  * `CapturedEventEmit` 직렬화/역직렬화 라운드트립 검증 테스트 추가
  * `ExploredPath`에 추가된 `capturedEventEmitIds`의 라운드트립 및 구버전 JSON 역직렬화 호환 검증 테스트 추가
  * `GraphAsset`에 추가된 `capturedEventEmits`의 라운드트립 및 구버전 JSON 역직렬화 호환 검증 테스트 추가

---

## 2. 작성 및 실행한 테스트 코드

`shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java` 파일의 끝부분에 추가된 테스트 메서드:

```java
    @Test
    void testCapturedEventEmitRoundTrip() throws Exception {
        CapturedEventEmit emit = new CapturedEventEmit("emit-1", "path-1", "order-topic", "user-1", Json.mapper().readTree("{\"status\":\"OK\"}"));
        CapturedEventEmit read = roundTrip(emit, CapturedEventEmit.class);
        assertThat(read.id()).isEqualTo("emit-1");
        assertThat(read.pathId()).isEqualTo("path-1");
        assertThat(read.topic()).isEqualTo("order-topic");
        assertThat(read.key()).isEqualTo("user-1");
        assertThat(read.payload().get("status").asText()).isEqualTo("OK");
    }

    @Test
    void exploredPath_capturedEventEmitIds_roundTripsAndDefaultsEmpty() throws Exception {
        ExploredPath path = new ExploredPath("p1", "e1", Json.mapper().createObjectNode(),
                200, Json.mapper().createObjectNode(), List.of(), List.of(), List.of(),
                "heuristic", List.of(), List.of(), List.of("seed-p1-1"), List.of("emit-1"));
        ExploredPath back = roundTrip(path, ExploredPath.class);
        assertThat(back.capturedEventEmitIds()).containsExactly("emit-1");

        String legacy = "{\"id\":\"p\",\"endpointId\":\"e\",\"sampleInput\":{},"
                + "\"expectedStatus\":200,\"sampleResponse\":{}}";
        assertThat(Json.mapper().readValue(legacy, ExploredPath.class).capturedEventEmitIds())
                .isEmpty();
    }

    @Test
    void graphAsset_capturedEventEmits_roundTripsAndDefaultsEmpty() throws Exception {
        CapturedEventEmit emit = new CapturedEventEmit("emit-1", "path-1", "order-topic", "user-1", Json.mapper().readTree("{\"status\":\"OK\"}"));
        GraphAsset asset = new GraphAsset(
                "order-service", "abc123",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(emit));
        GraphAsset back = roundTrip(asset, GraphAsset.class);
        assertThat(back.capturedEventEmits()).containsExactly(emit);

        String legacy = "{\"sutId\":\"s\",\"commitSha\":\"c\",\"endpoints\":[],\"paths\":[],\"sql\":[],\"tables\":[]}";
        GraphAsset legacyAsset = mapper.readValue(legacy, GraphAsset.class);
        assertThat(legacyAsset.capturedEventEmits()).isEmpty();
    }
```

---

## 3. 테스트 실행 결과 콘솔 출력

다음 명령을 통해 테스트를 재구동하고 통과했음을 검증하였습니다:
```bash
./gradlew :shared-model:clean :shared-model:test --tests io.graphrag.model.JsonRoundTripTest --no-build-cache
```

### 콘솔 출력
```text
> Task :shared-model:clean
> Task :shared-model:compileJava
> Task :shared-model:processResources NO-SOURCE
> Task :shared-model:classes
> Task :shared-model:compileTestJava
> Task :shared-model:processTestResources NO-SOURCE
> Task :shared-model:testClasses
> Task :shared-model:test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 4 executed
```
