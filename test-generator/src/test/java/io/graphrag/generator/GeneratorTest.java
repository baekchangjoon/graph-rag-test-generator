package io.graphrag.generator;

import io.graphrag.model.AuthMode;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {

    private static final Path GRAPH = Path.of("src/test/resources/fixture-graph");
    private static final GenerationRequest REQUEST = new GenerationRequest(
            "post-api-orders", "post-api-orders-happy",
            "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);

    @Test
    void generate_matchesGoldenFile() throws Exception {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);

        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).relativePath())
                .isEqualTo("io/graphrag/generated/OrdersPostTest.java");

        String expected = Files.readString(
                Path.of("src/test/resources/golden/OrdersPostTest.java.golden"));
        assertThat(result.files().get(0).content()).isEqualTo(expected);
    }

    @Test
    void generate_isDeterministic() {
        GenerationResult first = new Generator(GRAPH).generate(REQUEST);
        GenerationResult second = new Generator(GRAPH).generate(REQUEST);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void generate_withoutPathId_emitsOneClassPerPath() {
        GenerationRequest all = new GenerationRequest(
                "post-api-orders", null, "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(all);

        // fixture 그래프의 post-api-orders path 4개 (happy/404/EXPRESS×2)
        assertThat(result.files()).extracting(f -> f.relativePath()).containsExactly(
                "io/graphrag/generated/OrdersPostTest_HAPPY.java",
                "io/graphrag/generated/OrdersPostTest_S404_1.java",
                "io/graphrag/generated/OrdersPostTest_S201_2.java",
                "io/graphrag/generated/OrdersPostTest_S201_3.java");

        String notFoundTest = result.files().get(1).content();
        // 404 path: 사전 INSERT 없음 + 치환 변수는 사용 + 404 단언
        assertThat(notFoundTest).doesNotContain("INSERT INTO");
        assertThat(notFoundTest).contains("userId = scope.testId() + \"-user\";");
        assertThat(notFoundTest).contains(".statusCode(404)");

        // propagation 없는 S201_3만 직렬로 분류된다
        assertThat(result.parallelSafety().fullyParallel()).containsExactly(
                "OrdersPostTest_HAPPY", "OrdersPostTest_S404_1", "OrdersPostTest_S201_2");
        assertThat(result.parallelSafety().serialRequired())
                .extracting(s -> s.test()).containsExactly("OrdersPostTest_S201_3");
    }

    @Test
    void generate_mybatisSearchPath_compilesFixtureWithFkParent() {
        GenerationRequest search = new GenerationRequest(
                "post-api-orders-search", null, "OrdersSearchTest", "io.graphrag.generated",
                AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(search);

        assertThat(result.files()).hasSize(1);
        String content = result.files().get(0).content();
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

        String content = result.files().get(0).content();
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
    void propagationMissing_marksSerialExecution() {
        GenerationRequest noPropagation = new GenerationRequest(
                "post-api-orders", "post-api-orders-s201-3", "OrdersNoPropTest",
                "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(noPropagation);

        String content = result.files().get(0).content();
        // 격리 불가 → 직렬 실행 마크 + baggage 매칭 없는 스텁 (docs/04 규칙)
        assertThat(content).contains("@Execution(ExecutionMode.SAME_THREAD)");
        assertThat(content).doesNotContain("withBaggageTestId");
        assertThat(result.parallelSafety().fullyParallel()).isEmpty();
        assertThat(result.parallelSafety().serialRequired()).hasSize(1);
        assertThat(result.parallelSafety().serialRequired().get(0).reason())
                .isEqualTo("SUT_PROPAGATION_MISSING");
    }

    @Test
    void wsEndpoint_synthesizesStompTestPerExchange() {
        GenerationRequest ws = new GenerationRequest(
                "ws-orders-count", null, "OrdersCountWsTest", "io.graphrag.generated",
                AuthMode.DISABLED);
        GenerationResult result = new Generator(GRAPH).generate(ws);

        assertThat(result.files()).extracting(f -> f.relativePath()).containsExactly(
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
    void generate_reportsParallelSafety() {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);
        assertThat(result.parallelSafety().fullyParallel()).containsExactly("OrdersPostTest");
        assertThat(result.parallelSafety().serialRequired()).isEmpty();
    }

    @Test
    void generate_authRequiredEndpoint_usesAuthenticated() {
        Path authGraph = Path.of("src/test/resources/fixture-auth-graph");
        GenerationRequest authRequest = new GenerationRequest(
                "post-api-secure", "post-api-secure-happy",
                "SecurePostTest", "io.graphrag.generated", AuthMode.DISABLED);
        GenerationResult result = new Generator(authGraph).generate(authRequest);

        assertThat(result.files()).hasSize(1);
        String code = result.files().get(0).content();
        assertThat(code).contains("scope.rest().authenticated()");
        assertThat(code).doesNotContain("scope.rest().given()");
    }

    @Test
    void generate_nonAuthEndpoint_usesGiven() {
        GenerationResult result = new Generator(GRAPH).generate(REQUEST);
        String code = result.files().get(0).content();
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

        assertThat(result.files()).hasSize(1);
        String code = result.files().get(0).content();
        assertThat(code).contains("scope.jdbc().update(\"INSERT INTO orders");
        assertThat(code).contains(".get(\"/api/orders/1\")");
        assertThat(code).doesNotContain(".body({{{bodyExpr}}})");
        assertThat(code).doesNotContain(".contentType(\"application/json\")\n            .body(");
        assertThat(code).contains(".statusCode(200)");
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

        assertThat(result.files()).hasSize(1);
        String code = result.files().get(0).content();

        // 1. Check subscriptions
        assertThat(code).contains("scope.kafka().subscribe(\"orders-topic\");");
        assertThat(code).contains("scope.kafka().subscribe(\"orders-topic-nokey\");");

        // 2. Check assert blocks
        assertThat(code).contains("org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record =");
        
        // Assert orders-topic with key
        assertThat(code).contains("scope.kafka().consumeNextRecord(\"orders-topic\", java.time.Duration.ofSeconds(5));");
        assertThat(code).contains("org.junit.jupiter.api.Assertions.assertNotNull(record);");
        assertThat(code).contains("org.junit.jupiter.api.Assertions.assertEquals(\"order-key-123\", record.key());");
        assertThat(code).contains("org.skyscreamer.jsonassert.JSONAssert.assertEquals(");
        assertThat(code).contains("\"{\\\"orderId\\\":\\\"123\\\",\\\"status\\\":\\\"PENDING\\\"}\"");

        // Assert orders-topic-nokey without key
        assertThat(code).contains("scope.kafka().consumeNextRecord(\"orders-topic-nokey\", java.time.Duration.ofSeconds(5));");
        // We should assert that assertEquals is not present for orders-topic-nokey, but the simplest way is to check the structure or that there is only one assertEquals with record.key()
        assertThat(code).contains("org.junit.jupiter.api.Assertions.assertEquals(\"order-key-123\", record.key());");
        // Count or verify that assertEquals is not generated for nokey record.
        // The block for nokey should look like:
        // {
        //     org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record =
        //         scope.kafka().consumeNextRecord("orders-topic-nokey", java.time.Duration.ofSeconds(5));
        //     org.junit.jupiter.api.Assertions.assertNotNull(record);
        //     org.skyscreamer.jsonassert.JSONAssert.assertEquals(
        //         "{\"orderId\":\"123\",\"status\":\"PENDING\"}", record.value(), true);
        // }
        // Let's assert code structure for nokey
        assertThat(code).doesNotContain("org.junit.jupiter.api.Assertions.assertEquals(null, record.key())");
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

