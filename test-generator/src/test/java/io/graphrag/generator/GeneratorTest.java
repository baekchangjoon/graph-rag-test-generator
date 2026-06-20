package io.graphrag.generator;

import io.graphrag.model.AuthMode;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {

    private static final Path GRAPH = Path.of("src/test/resources/fixture-graph");
    private static final GenerationRequest REQUEST = new GenerationRequest(
            "post-api-orders", "post-api-orders-happy",
            "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);

    private static Map<String, String> byPath(GenerationResult result) {
        return result.files().stream().collect(Collectors.toMap(
                GeneratedFile::relativePath, GeneratedFile::content));
    }

    private static String onlyJava(GenerationResult result) {
        return result.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(GeneratedFile::content)
                .collect(Collectors.toList()).get(0);
    }

    @Test
    void generate_matchesGoldenFile() throws Exception {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);

        // 단일 path → 하나의 .java 클래스 + junit-platform.properties
        assertThat(result.files()).extracting(GeneratedFile::relativePath)
                .contains("io/graphrag/generated/OrdersPostTest.java");

        String expected = Files.readString(
                Path.of("src/test/resources/golden/OrdersPostTest.java.golden"));
        assertThat(byPath(result).get("io/graphrag/generated/OrdersPostTest.java"))
                .isEqualTo(expected);
    }

    @Test
    void generate_isDeterministic() {
        GenerationResult first = new Generator(GRAPH).generate(REQUEST);
        GenerationResult second = new Generator(GRAPH).generate(REQUEST);
        assertThat(first).isEqualTo(second);
    }

    /** REQ-001/003/011/012: endpoint의 병렬-안전 시나리오를 한 클래스에 메서드로 병합. */
    @Test
    void endpointMergesParallelScenariosIntoOneClass() {
        GenerationRequest all = new GenerationRequest(
                "post-api-orders", null, "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(all);

        Map<String, String> files = byPath(result);
        assertThat(files).containsKey("io/graphrag/generated/OrdersPostTest.java");
        String parallel = files.get("io/graphrag/generated/OrdersPostTest.java");

        // 3개의 병렬 @Test 메서드 (happy + s404_1 + s201_2), 직렬 메서드(s201_3)는 없음
        assertThat(parallel).contains("void happy()");
        assertThat(parallel).contains("void s404_1()");
        assertThat(parallel).contains("void s201_2()");
        assertThat(parallel).doesNotContain("void s201_3()");

        // 병합 클래스는 @Execution을 전혀 포함하지 않는다 (클래스/메서드 레벨 모두)
        assertThat(parallel).doesNotContain("@Execution");
        assertThat(parallel).doesNotContain("parallel.Execution");

        // 공유 @AfterEach는 정확히 scope.cleanup()만 호출
        assertThat(parallel).contains("@AfterEach\n    void cleanup() {\n        scope.cleanup();\n    }");
        assertThat(parallel).doesNotContain("scope.jdbc().update(\"DELETE");

        // 인스턴스-공유 없음 (@TestInstance / static TestScope 부재)
        assertThat(parallel).doesNotContain("@TestInstance");
        assertThat(parallel).doesNotContain("static TestScope");

        // cleanup은 deferDelete로 등록
        assertThat(parallel).contains("scope.jdbc().deferDelete(");
    }

    /** REQ-002: PROPAGATION-MISSING(직렬) 시나리오는 별도 …Serial 클래스로 분리. */
    @Test
    void propagationMissingScenarioGoesToSerialClass() {
        GenerationRequest all = new GenerationRequest(
                "post-api-orders", null, "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(all);

        Map<String, String> files = byPath(result);
        assertThat(files).containsKey("io/graphrag/generated/OrdersPostTestSerial.java");
        String serial = files.get("io/graphrag/generated/OrdersPostTestSerial.java");

        assertThat(serial).contains("void s201_3()");
        assertThat(serial).contains("@Execution(ExecutionMode.SAME_THREAD)\nclass OrdersPostTestSerial");
        assertThat(serial).contains("import org.junit.jupiter.api.parallel.Execution;");
        assertThat(serial).contains("import org.junit.jupiter.api.parallel.ExecutionMode;");
    }

    /** REQ-004: junit-platform.properties (strategy=dynamic) 방출. */
    @Test
    void emitsJunitPlatformPropertiesDynamic() {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);

        Map<String, String> files = byPath(result);
        assertThat(files).containsKey("junit-platform.properties");
        String props = files.get("junit-platform.properties");
        assertThat(props).contains("junit.jupiter.execution.parallel.enabled=true");
        assertThat(props).contains("junit.jupiter.execution.parallel.mode.default=concurrent");
        assertThat(props).contains("junit.jupiter.execution.parallel.mode.classes.default=concurrent");
        assertThat(props).contains("junit.jupiter.execution.parallel.config.strategy=dynamic");
        assertThat(props).contains("junit.jupiter.execution.parallel.config.dynamic.factor=1");
    }

    /** REQ-006: 병렬/직렬 클래스를 보고한다. */
    @Test
    void reportsParallelAndSerialClasses() {
        GenerationRequest all = new GenerationRequest(
                "post-api-orders", null, "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(all);

        assertThat(result.parallelSafety().fullyParallel()).containsExactly("OrdersPostTest");
        assertThat(result.parallelSafety().serialRequired()).hasSize(1);
        assertThat(result.parallelSafety().serialRequired().get(0).test())
                .isEqualTo("OrdersPostTestSerial");
        assertThat(result.parallelSafety().serialRequired().get(0).reason())
                .isEqualTo("SUT_PROPAGATION_MISSING");
    }

    /** REQ-007: 단일 직렬 path는 클래스 레벨 SAME_THREAD를 받는다. */
    @Test
    void singleSerialPathGetsClassLevelSameThread() {
        GenerationRequest noPropagation = new GenerationRequest(
                "post-api-orders", "post-api-orders-s201-3", "OrdersNoPropTest",
                "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(noPropagation);

        Map<String, String> files = byPath(result);
        assertThat(files).containsKey("io/graphrag/generated/OrdersNoPropTest.java");
        assertThat(files.get("io/graphrag/generated/OrdersNoPropTest.java"))
                .contains("@Execution(ExecutionMode.SAME_THREAD)\nclass OrdersNoPropTest");
        assertThat(files).containsKey("junit-platform.properties");
    }

    /** REQ-014: 메서드명은 endpoint prefix를 제거한다. */
    @Test
    void methodNameStripsEndpointPrefix() {
        GenerationRequest happy = new GenerationRequest(
                "post-api-orders", "post-api-orders-happy", "OrdersPostTest",
                "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(happy);
        assertThat(onlyJava(result)).contains("void happy()");
    }

    @Test
    void methodNameDerivationRules() {   // REQ-014
        assertThat(Generator.deriveMethodName("post-api-orders", "post-api-orders-happy")).isEqualTo("happy");
        assertThat(Generator.deriveMethodName("post-api-orders", "post-api-orders-s201-3")).isEqualTo("s201_3");
        // non-prefix fallback: full id used
        assertThat(Generator.deriveMethodName("post-api-orders", "other-scenario")).isEqualTo("other_scenario");
        // digit-start → s_ prefix (strip "ep-" → "404" → starts with digit)
        assertThat(Generator.deriveMethodName("ep", "ep-404")).isEqualTo("s_404");
    }

    /** REQ-014: 중복 메서드명 충돌은 _2 접미사로 고유화. */
    @Test
    void uniqueMethodNamesDedupesCollision() {
        assertThat(Generator.uniqueMethodNames(List.of("happy", "happy")))
                .containsExactly("happy", "happy_2");
    }

    @Test
    void generate_mybatisSearchPath_compilesFixtureWithFkParent() {
        GenerationRequest search = new GenerationRequest(
                "post-api-orders-search", null, "OrdersSearchTest", "io.graphrag.generated",
                AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(search);

        String content = onlyJava(result);
        assertThat(content).contains("INSERT INTO users (id, name) VALUES (?, ?)");
        assertThat(content).contains("INSERT INTO orders (user_id, amount, type, status)");
        assertThat(content).contains(".post(\"/api/orders/search\")");
    }

    @Test
    void httpCallPath_synthesizesWireMockStubWithBaggage() {
        GenerationRequest express = new GenerationRequest(
                "post-api-orders", "post-api-orders-s201-2", "OrdersExpressTest",
                "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(express);

        String content = onlyJava(result);
        assertThat(content)
                .contains("scope.http().stub(\"GET\", \"/inventory/stock\")")
                .contains(".withQueryParam(\"type\", \"EXPRESS\")")
                .contains(".withBaggageTestId(scope.testId())")
                // consumedFields 투영: warehouse는 빠지고 available만
                .contains(".respondJson(200, \"{\\\"available\\\":50}\")")
                .doesNotContain("warehouse")
                .contains(".register();");
        assertThat(content).doesNotContain("@Execution");
        assertThat(result.parallelSafety().fullyParallel()).containsExactly("OrdersExpressTest");
    }

    @Test
    void generate_authRequiredEndpoint_usesAuthenticated() {
        Path authGraph = Path.of("src/test/resources/fixture-auth-graph");
        GenerationRequest authRequest = new GenerationRequest(
                "post-api-secure", "post-api-secure-happy",
                "SecurePostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(authGraph).generate(authRequest);

        String code = onlyJava(result);
        assertThat(code).contains("scope.rest().authenticated()");
        assertThat(code).doesNotContain("scope.rest().given()");
    }

    @Test
    void generate_nonAuthEndpoint_usesGiven() {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);
        String code = onlyJava(result);
        assertThat(code).contains("scope.rest().given()");
        assertThat(code).doesNotContain("scope.rest().authenticated()");
    }

    @Test
    void generate_readPathGet_seedInsertLiteralUrlNoBody() {
        Path readGraph = Path.of("src/test/resources/fixture-read-path-graph");
        GenerationRequest readRequest = new GenerationRequest(
                "get-api-orders-id", "get-api-orders-id-happy",
                "OrdersGetTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(readGraph).generate(readRequest);

        String code = onlyJava(result);
        assertThat(code).contains("scope.jdbc().update(\"INSERT INTO orders");
        assertThat(code).contains(".get(\"/api/orders/1\")");
        assertThat(code).doesNotContain(".body({{{bodyExpr}}})");
        assertThat(code).doesNotContain(".contentType(\"application/json\")\n            .body(");
        assertThat(code).contains(".statusCode(200)");
    }

    @Test
    void wsEndpoint_synthesizesStompTestPerExchange() {
        GenerationRequest ws = new GenerationRequest(
                "ws-orders-count", null, "OrdersCountWsTest", "io.graphrag.generated",
                AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(ws);

        assertThat(result.files()).extracting(GeneratedFile::relativePath).containsExactly(
                "io/graphrag/generated/OrdersCountWsTest_X1.java",
                "io/graphrag/generated/OrdersCountWsTest_X2.java");

        String happy = result.files().get(0).content();
        assertThat(happy)
                .contains("scope.stomp(\"/ws\")")
                .contains("stomp.subscribe(\"/topic/orders\")")
                .contains("stomp.send(\"/app/orders/count\", "
                        + "String.format(\"{\\\"userId\\\":\\\"%s\\\"}\", userId))")
                // 응답이 치환 값을 echo → 마커 기반 병렬 격리
                .contains("stomp.awaitMessageContaining(userId,")
                .contains("INSERT INTO users (id, name) VALUES (?, ?)")
                .contains("assertTrue(response.has(\"count\"))");
        assertThat(result.parallelSafety().fullyParallel())
                .containsExactly("OrdersCountWsTest_X1", "OrdersCountWsTest_X2");
    }

    @Test
    void generate_withKafkaOutboundEvents_includesAssertions() {
        io.graphrag.model.Endpoint endpoint = new io.graphrag.model.Endpoint(
                "post-api-orders", "POST", "/api/orders",
                "OrderController", "create", java.util.List.of(), false);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode()
                .put("orderId", "123")
                .put("status", "PENDING");

        io.graphrag.model.ExploredPath path = new io.graphrag.model.ExploredPath(
                "post-api-orders-happy", "post-api-orders",
                mapper.createObjectNode(), 200, mapper.createObjectNode(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), "test", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of("emit-1", "emit-2")
        );

        io.graphrag.model.CapturedEventEmit emit1 = new io.graphrag.model.CapturedEventEmit(
                "emit-1", "post-api-orders-happy", "orders-topic", "order-key-123", payload
        );

        io.graphrag.model.CapturedEventEmit emit2 = new io.graphrag.model.CapturedEventEmit(
                "emit-2", "post-api-orders-happy", "orders-topic-nokey", null, payload
        );

        io.graphrag.model.GraphAsset asset = new io.graphrag.model.GraphAsset(
                "sut", "commit",
                java.util.List.of(endpoint),
                java.util.List.of(path),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(emit1, emit2)
        );

        FakeGraphRagClient fakeClient = new FakeGraphRagClient(asset);
        GenerationRequest request = new GenerationRequest(
                "post-api-orders", "post-api-orders-happy",
                "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);

        GenerationResult result = new Generator(fakeClient).generate(request);

        String code = onlyJava(result);

        // 1. 두 토픽 모두 구독
        assertThat(code).contains("scope.kafka().subscribe(\"orders-topic\");");
        assertThat(code).contains("scope.kafka().subscribe(\"orders-topic-nokey\");");

        // 2. key 있는 emit: expectedKey 인자로 자기 발행 레코드만 소비(공유 토픽 격리).
        //    이 path는 입력/치환이 없어 key는 리터럴, payload는 그대로 LENIENT 비교.
        assertThat(code).contains(
                "scope.kafka().consumeNextRecord(\"orders-topic\", \"order-key-123\", java.time.Duration.ofSeconds(5));");
        assertThat(code).contains("org.junit.jupiter.api.Assertions.assertNotNull(record);");
        assertThat(code).contains("\"{\\\"orderId\\\":\\\"123\\\",\\\"status\\\":\\\"PENDING\\\"}\"");

        // 3. key 없는 emit: expectedKey = null (필터 없음)
        assertThat(code).contains(
                "scope.kafka().consumeNextRecord(\"orders-topic-nokey\", null, java.time.Duration.ofSeconds(5));");

        // 4. 중복 key 단언은 더 이상 생성하지 않는다(consumeNextRecord가 key로 필터링).
        assertThat(code).doesNotContain("record.key());");
        // 5. payload 비교는 LENIENT CustomComparator — strict(true) 단언은 없어야 하고, CustomComparator LENIENT가 있어야 한다.
        assertThat(code).doesNotContain("record.value(), true);");
        assertThat(code).contains("record.value(),");
        assertThat(code).contains("CustomComparator");
        assertThat(code).contains("LENIENT");
    }

    static class FakeGraphRagClient implements io.graphrag.generator.client.GraphRagClient {
        private final io.graphrag.model.GraphAsset asset;

        FakeGraphRagClient(io.graphrag.model.GraphAsset asset) {
            this.asset = asset;
        }

        @Override
        public io.graphrag.model.Endpoint endpoint(String id) {
            return asset.endpoints().stream().filter(e -> e.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown endpoint: " + id));
        }

        @Override
        public io.graphrag.model.ExploredPath path(String id) {
            return asset.paths().stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown path: " + id));
        }

        @Override
        public java.util.List<io.graphrag.model.ExploredPath> pathsForEndpoint(String endpointId) {
            return asset.paths().stream().filter(p -> p.endpointId().equals(endpointId)).toList();
        }

        @Override
        public java.util.List<io.graphrag.model.CapturedSql> sqlForPath(String pathId) {
            return asset.sql().stream().filter(s -> s.pathId().equals(pathId)).toList();
        }

        @Override
        public java.util.List<io.graphrag.model.CapturedHttpCall> httpCallsForPath(String pathId) {
            return asset.httpCalls().stream().filter(c -> c.pathId().equals(pathId)).toList();
        }

        @Override
        public boolean hasWsEndpoint(String id) {
            return asset.wsEndpoints().stream().anyMatch(w -> w.id().equals(id));
        }

        @Override
        public io.graphrag.model.WsEndpoint wsEndpoint(String id) {
            return asset.wsEndpoints().stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
        }

        @Override
        public java.util.List<io.graphrag.model.WsExchange> wsExchangesFor(String wsEndpointId) {
            return asset.wsExchanges().stream().filter(w -> w.wsEndpointId().equals(wsEndpointId)).toList();
        }

        @Override
        public io.graphrag.model.WsExchange wsExchange(String exchangeId) {
            return asset.wsExchanges().stream().filter(w -> w.id().equals(exchangeId)).findFirst().orElse(null);
        }

        @Override
        public boolean hasKafkaConsumer(String id) {
            return asset.kafkaConsumers().stream().anyMatch(k -> k.id().equals(id));
        }

        @Override
        public io.graphrag.model.KafkaConsumer kafkaConsumer(String id) {
            return asset.kafkaConsumers().stream().filter(k -> k.id().equals(id)).findFirst().orElse(null);
        }

        @Override
        public java.util.List<io.graphrag.model.KafkaExchange> kafkaExchangesFor(String consumerId) {
            return asset.kafkaExchanges().stream().filter(x -> x.kafkaConsumerId().equals(consumerId)).toList();
        }

        @Override
        public java.util.List<io.graphrag.model.TableSchema> tables() {
            return asset.tables();
        }

        @Override
        public java.util.List<io.graphrag.model.RequiredSeed> seedsForPath(String pathId) {
            return asset.seeds().stream().filter(s -> java.util.Objects.equals(s.pathId(), pathId)).toList();
        }

        @Override
        public java.util.List<io.graphrag.model.CapturedEventEmit> capturedEventEmitsForPath(String pathId) {
            return asset.capturedEventEmits().stream().filter(e -> e.pathId().equals(pathId)).toList();
        }
    }
}
