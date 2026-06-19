package io.graphrag.testlib.api;

import io.graphrag.model.EventType;
import io.graphrag.model.Json;
import io.graphrag.model.TestEvent;
import io.graphrag.testlib.adapter.dashboard.DashboardReporters;
import io.graphrag.testlib.spi.Adapters;
import io.graphrag.testlib.spi.AuthAdapter;
import io.graphrag.testlib.spi.DashboardReporter;
import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.HttpMockAdapter;
import io.graphrag.testlib.spi.JdbcAdapter;
import io.graphrag.testlib.spi.SocketMockAdapter;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 테스트 1건의 자원 스코프. unique testId 발급 + 어댑터 와이어링 + cleanup 보장.
 * 생성 테스트 코드가 사용하는 유일한 진입점 (docs/07).
 */
public final class TestScope {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RUN_ID = UUID.randomUUID().toString();

    private final String testId;
    private final JdbcHelper jdbc;
    private final RestAssuredHelper rest;
    private final HttpMockClient http;
    private final SocketMockClient socket;
    private final AuthClient auth;
    private final DashboardReporter dashboard;
    private final String appBaseUri;
    private final String kafkaBootstrap;   // nullable — Kafka 생성 테스트용
    private final java.util.List<StompHelper> stompHelpers = new java.util.ArrayList<>();
    private final java.util.List<KafkaHelper> kafkaHelpers = new java.util.ArrayList<>();
    private KafkaHelper kafkaSingleton;   // lazy singleton for kafka()
    private boolean cleaned;

    private TestScope(String testId, JdbcHelper jdbc, RestAssuredHelper rest,
                      HttpMockClient http, SocketMockClient socket, AuthClient auth,
                      DashboardReporter dashboard, String appBaseUri, String kafkaBootstrap) {
        this.testId = testId;
        this.jdbc = jdbc;
        this.rest = rest;
        this.http = http;
        this.socket = socket;
        this.auth = auth;
        this.dashboard = dashboard;
        this.appBaseUri = appBaseUri;
        this.kafkaBootstrap = kafkaBootstrap;
    }

    public static TestScope create() {
        return create(Env.fromSystem());
    }

    public static TestScope create(Env env) {
        String appBaseUri = env.require("APP_BASE_URI");
        env.require("JDBC_URL");

        String testId = "t-" + HexFormat.of().toHexDigits(RANDOM.nextInt(), 8);
        String runId = env.getOrDefault("TEST_RUN_ID", RUN_ID);

        DashboardReporter dashboard = DashboardReporters.fromEnv(env);
        JdbcAdapter jdbcAdapter = Adapters.select(JdbcAdapter.class, "JDBC_ADAPTER", "plain", env);
        // 기본 어댑터는 WireMock (docs/07). admin URL이 없으면 noop으로 폴백
        String defaultHttpMock = env.get("HTTP_MOCK_ADMIN") != null ? "wiremock" : "noop";
        HttpMockAdapter httpAdapter = Adapters.select(HttpMockAdapter.class, "HTTP_MOCK_ADAPTER", defaultHttpMock, env);
        SocketMockAdapter socketAdapter = Adapters.select(SocketMockAdapter.class, "SOCKET_MOCK_ADAPTER", "noop", env);
        AuthAdapter authAdapter = Adapters.select(AuthAdapter.class, "AUTH_ADAPTER", "noop", env);

        AuthClient authClient = authAdapter.create(env);
        String headerLines = env.getOrDefault("REQUEST_HEADERS", "");
        io.graphrag.model.RequestHeaders requestHeaders = headerLines.isBlank()
                ? io.graphrag.model.RequestHeaders.empty()
                : io.graphrag.model.RequestHeaders.parse(
                        java.util.List.of(headerLines.split("\\R")),
                        env.get("REQUEST_HEADERS_ON_LOGIN") != null);
        TestScope scope = new TestScope(
                testId,
                new JdbcHelper(jdbcAdapter, env, testId, runId, dashboard),
                new RestAssuredHelper(appBaseUri, testId, authClient,
                        env.getOrDefault("AUTH_HEADER", "Authorization"),
                        env.getOrDefault("AUTH_SCHEME", "Bearer"),
                        env.getOrDefault("AUTH_USER", "admin"),
                        env.getOrDefault("AUTH_PASS", "password"),
                        requestHeaders),
                httpAdapter.create(env, testId),
                socketAdapter.create(env, testId),
                authClient,
                dashboard,
                appBaseUri,
                env.get("KAFKA_BOOTSTRAP_SERVERS"));
        dashboard.report(new TestEvent(EventType.SCOPE_CREATED, testId, runId,
                Instant.now(), Json.mapper().nullNode()));
        return scope;
    }

    public String testId() {
        return testId;
    }

    public JdbcHelper jdbc() {
        return jdbc;
    }

    public RestAssuredHelper rest() {
        return rest;
    }

    public HttpMockClient http() {
        return http;
    }

    public SocketMockClient socket() {
        return socket;
    }

    public AuthClient auth() {
        return auth;
    }

    /** STOMP 연결을 연다. cleanup 시 자동으로 닫힌다. */
    public StompHelper stomp(String wsPath) {
        StompHelper helper = StompHelper.connect(appBaseUri, wsPath,
                java.time.Duration.ofSeconds(10));
        stompHelpers.add(helper);
        return helper;
    }

    /** Kafka helper를 반환한다 (lazy singleton — subscribe와 consume이 같은 인스턴스를 사용). */
    public KafkaHelper kafka() {
        if (kafkaBootstrap == null || kafkaBootstrap.isBlank()) {
            throw new IllegalStateException("KAFKA_BOOTSTRAP_SERVERS not set");
        }
        if (kafkaSingleton == null) {
            kafkaSingleton = new KafkaHelper(kafkaBootstrap);
            kafkaHelpers.add(kafkaSingleton);
        }
        return kafkaSingleton;
    }

    /** 자기 스코프의 등록된 DB 정리(deferred delete)를 등록 순서대로(deferred delete) 실행한 뒤 mock/연결을 해제. */
    public void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        try {
            jdbc.runDeferredDeletes();
            stompHelpers.forEach(StompHelper::close);
            kafkaHelpers.forEach(KafkaHelper::close);
            http.removeAllForScope(testId);
            socket.removeSession(testId);
        } finally {
            jdbc.close();
        }
        dashboard.report(new TestEvent(EventType.SCOPE_CLEANED, testId,
                RUN_ID, Instant.now(), Json.mapper().nullNode()));
    }
}
