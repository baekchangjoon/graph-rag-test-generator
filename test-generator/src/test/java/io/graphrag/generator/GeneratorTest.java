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
}
