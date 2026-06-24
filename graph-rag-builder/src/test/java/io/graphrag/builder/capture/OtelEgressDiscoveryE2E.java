package io.graphrag.builder.capture;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.capture.egress.EgressCollector;
import io.graphrag.builder.coverage.OtelAgent;
import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutOptions;
import io.graphrag.model.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-009/008/011: OTEL 모드에서 redirect/WireMock 없이 egress outbound(InventoryClient)를
 * CLIENT span으로 발견한다.
 *
 * <p>order-service를 OTEL 모드로 기동. EXTERNAL_INVENTORY_URL은 테스트가 직접 띄운
 * 호스트 stub(HttpServer on localhost)을 가리킨다 — WireMock 치환 미사용.
 *
 * <p>트리거: POST /api/orders {type=EXPRESS} → InventoryClient.check() → GET /inventory/stock
 * OTEL javaagent가 이 outbound를 CLIENT span으로 기록 → EgressCollector가 수집.
 *
 * <p>Docker(Testcontainers Postgres) 필요. {@code -Dsut.jar=...} 필요.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtelEgressDiscoveryE2E {

    private static final String INVENTORY_RESPONSE =
            "{\"available\":5,\"mode\":\"EXPRESS\"}";

    private HttpServer inventoryStub;
    private AnalysisEnvironment env;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void startEnvironment() throws Exception {
        // 1. 호스트 inventory stub: GET /inventory/stock → 200 + InventoryResponse JSON
        //    order-service는 호스트 자식 프로세스이므로 127.0.0.1로 도달 가능.
        inventoryStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inventoryStub.createContext("/inventory/stock", exchange -> {
            byte[] body = INVENTORY_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        inventoryStub.start();
        String inventoryBaseUrl = "http://127.0.0.1:" + inventoryStub.getAddress().getPort();

        // 2. AnalysisEnvironment: OTEL 모드, externalStubsDir=null(redirect 미사용)
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path out = Files.createTempDirectory("otel-egress-e2e");
        Path workDir = out.resolve("work");
        Files.createDirectories(workDir);

        DbConfig dbConfig = new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app");
        env = new AnalysisEnvironment(dbConfig, false, false);

        OtelAgent otel = OtelAgent.prepare(workDir);
        SutOptions sutOptions = new SutOptions(
                otel.javaToolOptions(), Map.of(), otel.env("order-service"), null);

        // EXTERNAL_INVENTORY_URL을 stub URL로 직접 설정. WIREMOCK_PLACEHOLDER 미사용.
        // externalStubsDir=null, sutEnvTemplate에 직접 URL을 넣어 치환 없이 전달.
        env.start(sutJar, workDir, sutOptions, null,
                Map.of("EXTERNAL_INVENTORY_URL", inventoryBaseUrl),
                otel, "order-service");

        base = env.sut().baseUri();

        // 3. 주문 생성을 위한 사용자 시드 삽입 (order-service는 userId를 users 테이블에서 조회)
        try (java.sql.Connection conn = env.openConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT INTO users(id, name) VALUES('admin', 'Administrator') ON CONFLICT DO NOTHING");
        }
    }

    @AfterAll
    void stopEnvironment() {
        try {
            if (env != null) {
                env.close();
            }
        } finally {
            if (inventoryStub != null) {
                inventoryStub.stop(0);
            }
        }
    }

    /**
     * REQ-009: OTEL 모드에서 outbound inventory 호출(GET /inventory/stock)이
     * CLIENT span으로 기록되어 EgressCollector가 발견한다.
     * REQ-011: redirect/WireMock 미사용 — EXTERNAL_INVENTORY_URL이 직접 호스트 stub.
     */
    @Test
    @DisplayName("REQ-009/008/011: otel 모드 redirect-비의존 egress 발견 — GET /inventory/stock CLIENT span")
    void otelMode_discoversInventoryEgressWithoutRedirect() throws Exception {
        // traceparent 주입으로 이 요청의 span을 추적한다.
        String runId = "egress-e2e-" + System.nanoTime();
        TraceParent.Ids injected = new TraceParent(runId).next();
        String traceId = injected.traceId();

        // 인증 토큰 취득
        String token = login();

        // POST /api/orders {userId:admin, amount:1, type:EXPRESS} → InventoryClient.check() 유발
        String orderBody = "{\"userId\":\"admin\",\"amount\":1,\"type\":\"EXPRESS\"}";
        HttpResponse<String> postResponse = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/orders"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .header("traceparent", injected.header())
                        .POST(HttpRequest.BodyPublishers.ofString(orderBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        System.out.println("=== POST /api/orders status=" + postResponse.statusCode()
                + " body=" + postResponse.body() + " ===");
        // 201(CREATED) 정상 케이스 목표. 그 외:
        //   409 — inventory 분기(BACKORDER/재고부족) → inventory 호출은 발생했음
        //   500 — Kafka 미설정 등 부가 경로 오류(inventory CLIENT span은 이미 완료 후 실패)
        // 중요: inventory GET이 나갔으면 어느 상태든 egress span은 존재
        assertThat(postResponse.statusCode()).isIn(200, 201, 409, 404, 400, 500);

        // EgressCollector로 CLIENT span 수집 (quiescence await 포함)
        EgressCollector collector = EgressCollector.forMode(env);
        assertThat(collector).as("OTEL 모드이므로 EgressCollector가 non-null").isNotNull();

        List<EgressCall> egressCalls = collector.collect(traceId);

        System.out.println("=== egress calls for trace " + traceId + " ===");
        egressCalls.forEach(c -> System.out.println("  " + c.method() + " " + c.path()
                + " status=" + c.statusOrNull()));

        // 핵심 검증: GET /inventory/stock이 egress로 발견되어야 한다
        assertThat(egressCalls)
                .as("redirect 없이 inventory CLIENT span(GET /inventory/stock)이 발견되어야 함")
                .anyMatch(call -> "GET".equals(call.method())
                        && "/inventory/stock".equals(call.path()));
    }

    private String login() throws Exception {
        HttpResponse<String> loginResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"admin\",\"password\":\"password\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(loginResp.statusCode()).isEqualTo(200);
        String token = Json.mapper().readTree(loginResp.body()).path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }
}
