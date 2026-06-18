package io.graphrag.builder.capture;

import io.graphrag.builder.coverage.OtelAgent;
import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.env.SutOptions;
import io.graphrag.model.Json;
import io.graphrag.model.Endpoint;
import io.graphrag.builder.run.EndpointExplorationRunner;
import io.graphrag.builder.explore.InvocationOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수용-1/2 (plan Task 4.6): HTTP OTEL 캡처 parity + 동시성 귀속.
 *
 * <p>db.statement(구 semconv) 수정 후 HTTP 경로가 실제로 OTEL span에서 SQL을 환원하는지
 * (로그 폴백이 아니라) 그리고 동시 요청이 trace-id로 서로 격리 귀속되는지 증명한다.
 * order-service를 OTEL 모드로 1회 띄워 두 테스트가 공유한다(PER_CLASS). Docker 필요.
 *
 * <p>SQL 유발 타겟: {@code GET /api/orders/{id}} → {@code select ... from orders where id=?}
 * (행 유무와 무관하게 SELECT 실행, path-var가 bind로 귀속). drain은 noopSut로 폴백을 끈다.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtelHttpCaptureAcceptanceTest {

    private Path out;
    private AnalysisEnvironment env;
    private String base;
    private String token;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void startSut() throws Exception {
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path externalStubs = Path.of(System.getProperty("external.stubs"));
        out = Files.createTempDirectory("otel-http-acc");
        Path workDir = out.resolve("work");
        Files.createDirectories(workDir);

        DbConfig dbConfig = new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app");
        env = new AnalysisEnvironment(dbConfig, false, false);
        OtelAgent otel = OtelAgent.prepare(workDir);
        SutOptions sutOptions = new SutOptions(
                otel.javaToolOptions(), Map.of(), otel.env("order-service"), null);
        env.start(sutJar, workDir, sutOptions, externalStubs,
                Map.of("EXTERNAL_INVENTORY_URL", AnalysisEnvironment.WIREMOCK_PLACEHOLDER),
                otel, "order-service");
        base = env.sut().baseUri();
        token = login();
    }

    @AfterAll
    void stopSut() {
        if (env != null) {
            env.close();
        }
    }

    /** 수용-1: HTTP 요청 SQL이 OTEL span에서 환원된다(폴백 아님). */
    @Test
    void httpRequest_capturesSqlViaOtelNotFallback() throws Exception {
        String runId = "acc1-http";
        TraceParent.Ids injected = new TraceParent(runId).next();

        getOrder("99999", injected.header());

        OtelSpanCapture pipeline =
                new OtelSpanCapture(env.otlpReceiver(), noopSut(), new TraceParent(runId));
        List<ParsedSql> captured = pipeline.begin().drain();

        System.out.println("=== 수용-1 HTTP drain -> " + captured.size() + " ParsedSql ===");
        captured.forEach(p -> System.out.println("  " + p.sql() + "  binds="
                + p.bindings().stream().map(ParsedSql.Binding::value).toList()));

        assertThat(captured).as("HTTP OTEL 경로가 SQL을 OTEL span에서 환원(폴백 아님)").isNotEmpty();
        assertThat(captured).as("orders 조회 SELECT").anyMatch(p -> p.sql().toLowerCase().contains("orders"));
        assertThat(captured).as("path-var 99999가 bind로 귀속")
                .anyMatch(p -> hasBinding(p, "99999"));
    }

    /** 수용-2: 서로 다른 traceparent의 두 요청을 동시 발행 → 각 drain이 자기 trace의 SQL만 (교차 오염 0). */
    @Test
    void concurrentRequests_attributeSqlPerTraceWithoutBleed() throws Exception {
        OtelSpanCapture capture =
                new OtelSpanCapture(env.otlpReceiver(), noopSut(), new TraceParent("acc2-concurrent"));
        OtelSpanCapture.OtelScope a = (OtelSpanCapture.OtelScope) capture.begin();
        OtelSpanCapture.OtelScope b = (OtelSpanCapture.OtelScope) capture.begin();
        String tpA = a.requestHeaders().get("traceparent");
        String tpB = b.requestHeaders().get("traceparent");

        // 동시 발행: 두 스레드가 래치로 동시에 출발해 인터리브를 만든다.
        CountDownLatch start = new CountDownLatch(1);
        Thread tA = new Thread(() -> awaitThenGet(start, "11111", tpA));
        Thread tB = new Thread(() -> awaitThenGet(start, "22222", tpB));
        tA.start();
        tB.start();
        start.countDown();
        tA.join();
        tB.join();

        List<ParsedSql> sqlA = a.drain();
        List<ParsedSql> sqlB = b.drain();
        System.out.println("=== 수용-2 A binds=" + binds(sqlA) + " | B binds=" + binds(sqlB) + " ===");

        assertThat(sqlA).as("A는 자기 id 11111을 귀속").anyMatch(p -> hasBinding(p, "11111"));
        assertThat(sqlA).as("A에 B의 id 22222가 섞이지 않음")
                .noneMatch(p -> hasBinding(p, "22222"));
        assertThat(sqlB).as("B는 자기 id 22222를 귀속").anyMatch(p -> hasBinding(p, "22222"));
        assertThat(sqlB).as("B에 A의 id 11111이 섞이지 않음")
                .noneMatch(p -> hasBinding(p, "11111"));
    }

    private void awaitThenGet(CountDownLatch start, String id, String traceparent) {
        try {
            start.await();
            getOrder(id, traceparent);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void getOrder(String id, String traceparent) throws Exception {
        HttpResponse<String> get = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/orders/" + id))
                        .header("Authorization", "Bearer " + token)
                        .header("traceparent", traceparent)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isIn(200, 404);
    }

    private String login() throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"admin\",\"password\":\"password\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String t = Json.mapper().readTree(login.body()).path("token").asText();
        assertThat(t).isNotBlank();
        return t;
    }

    private static boolean hasBinding(ParsedSql p, String value) {
        return p.bindings().stream().anyMatch(b -> value.equals(b.value()));
    }

    private static List<String> binds(List<ParsedSql> sql) {
        return sql.stream().flatMap(p -> p.bindings().stream())
                .map(ParsedSql.Binding::value).toList();
    }

    /** 폴백 비활성용 noop SutHandle — drain()이 OTEL 스팬만으로 환원함을 보장. */
    private static SutHandle noopSut() {
        return new SutHandle() {
            public String baseUri() { return ""; }
            public String readLog() { return ""; }
            public long logOffset() { return 0; }
            public String readLogFrom(long o) { return ""; }
            public String readLogRange(long s, long e) { return ""; }
            public void stop() { }
        };
    }

    private String login(String baseUri) throws Exception {
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(URI.create(baseUri + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"admin\",\"password\":\"password\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String t = Json.mapper().readTree(login.body()).path("token").asText();
        assertThat(t).isNotBlank();
        return t;
    }

    @Test
    void httpRequest_capturesOutboundKafkaEvent() throws Exception {
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path externalStubs = Path.of(System.getProperty("external.stubs"));
        Path workDir = out.resolve("work-kafka-test");
        Files.createDirectories(workDir);

        DbConfig dbConfig = new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app");
        String runId = "acc3-kafka-emit";
        TraceParent.Ids injected = new TraceParent(runId).next();

        try (AnalysisEnvironment kafkaEnv = new AnalysisEnvironment(dbConfig, false, true)) {
            OtelAgent otel = OtelAgent.prepare(workDir);
            SutOptions sutOptions = new SutOptions(
                    otel.javaToolOptions(), Map.of(), otel.env("order-service"), null);
            kafkaEnv.start(sutJar, workDir, sutOptions, externalStubs,
                    Map.of("EXTERNAL_INVENTORY_URL", AnalysisEnvironment.WIREMOCK_PLACEHOLDER),
                    otel, "order-service");

            String kafkaBootstrap = kafkaEnv.kafkaBootstrapServers();
            assertThat(kafkaBootstrap).isNotBlank();

            String customBase = kafkaEnv.sut().baseUri();
            try (java.sql.Connection conn = kafkaEnv.openConnection();
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO users(id, name) VALUES('admin', 'Administrator') ON CONFLICT DO NOTHING");
            }

            String actualToken = login(customBase);
            io.graphrag.builder.run.AuthConfig authConfig = new io.graphrag.builder.run.AuthConfig(
                    "/api/auth/login", "admin", "password", "token", "Authorization", "Bearer", List.of());

            Endpoint endpoint = new Endpoint("post-orders", "POST", "/api/orders",
                    "io.graphrag.sample.orders.OrderController", "create", List.of(), true);
            com.fasterxml.jackson.databind.JsonNode input = Json.mapper().readTree(
                    "{\"userId\":\"admin\",\"amount\":10,\"type\":\"NORMAL\"}");

            try (io.graphrag.builder.run.KafkaCaptureReceiver receiver =
                         new io.graphrag.builder.run.KafkaCaptureReceiver(kafkaBootstrap)) {
                receiver.start();

                try (java.sql.Connection conn = kafkaEnv.openConnection()) {
                    EndpointExplorationRunner runner = new EndpointExplorationRunner(
                            kafkaEnv.sut(), conn, kafkaEnv.dbType(),
                            new io.graphrag.builder.coverage.CoverageClient("localhost", 0) {
                                @Override
                                public org.jacoco.core.data.ExecutionDataStore dump(boolean reset) {
                                    return new org.jacoco.core.data.ExecutionDataStore();
                                }
                            },
                            new io.graphrag.builder.coverage.BranchCoverageAnalyzer(sutJar),
                            1, kafkaEnv.httpCapture(), List.of(), List.of(),
                            new io.graphrag.builder.run.AuthTokenProvider(customBase, authConfig, io.graphrag.model.RequestHeaders.empty()),
                            authConfig, Map.of(), Map.of(), io.graphrag.model.RequestHeaders.empty(),
                            new OtelSpanCapture(kafkaEnv.otlpReceiver(), kafkaEnv.sut(), new TraceParent(runId)),
                            receiver
                    );

                    java.lang.reflect.Method doSendMethod = EndpointExplorationRunner.class.getDeclaredMethod(
                            "doSend", HttpClient.class, Endpoint.class, com.fasterxml.jackson.databind.JsonNode.class, String.class);
                    doSendMethod.setAccessible(true);

                    InvocationOutcome outcome = (InvocationOutcome) doSendMethod.invoke(
                            runner, HttpClient.newHttpClient(), endpoint, input, "Bearer " + actualToken);

                    assertThat(outcome.capturedEventEmits()).isNotEmpty();
                    io.graphrag.model.CapturedEventEmit emit = outcome.capturedEventEmits().get(0);
                    assertThat(emit.topic()).isEqualTo("order.events");
                    assertThat(emit.key()).isEqualTo("admin");
                    assertThat(emit.payload().path("type").asText()).isEqualTo("CREATED");
                    assertThat(emit.payload().path("userId").asText()).isEqualTo("admin");
                }
            }
        }
    }
}
