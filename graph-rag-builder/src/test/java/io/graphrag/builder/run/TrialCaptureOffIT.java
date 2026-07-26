package io.graphrag.builder.run;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.builder.capture.SqlCaptureBackend;
import io.graphrag.builder.coverage.CoverageProbe;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.InvocationOutcome;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import io.graphrag.model.RequestHeaders;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-015: trial invoke(캡처-off no-op scope)가 SQL 캡처 scope를 열지 않고, 요청별 JaCoCo dump를
 * 스킵하며, cumulativeCoverage/graph 산출물에 어떤 것도 병합하지 않는지 검증한다.
 *
 * <p>{@link OutcomeGatingTest}처럼 실 SUT/DB 없이 fake invoker/backend로 배선을 확인하는
 * 최소-의존 패턴을 재사용한다 — 실 HTTP 서버(com.sun.net.httpserver)만 SUT 대역으로 띄우고,
 * {@link SqlCaptureBackend}/{@link CoverageProbe}는 호출 여부를 기록하는 fake로 대체한다.
 */
class TrialCaptureOffIT {

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger requestCount = new AtomicInteger();

    @BeforeAll
    static void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/items", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterAll
    static void down() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void resetRequestCount() {
        requestCount.set(0);
    }

    /** sqlCapture.begin() 호출 횟수를 기록하는 fake — invokeTrial은 이를 절대 호출하면 안 된다. */
    private static final class RecordingSqlCaptureBackend implements SqlCaptureBackend {
        private final AtomicInteger beginCalls = new AtomicInteger();

        @Override
        public Scope begin() {
            beginCalls.incrementAndGet();
            return new Scope() {
                @Override
                public Map<String, String> requestHeaders() {
                    return Map.of();
                }

                @Override
                public List<ParsedSql> drain() {
                    return List.of();
                }
            };
        }
    }

    /** coverage.baselineCut/requestDelta 호출 횟수를 기록하는 fake — invokeTrial은 이들을 절대 호출하면 안 된다. */
    private static final class RecordingCoverageProbe implements CoverageProbe {
        private final AtomicInteger baselineCutCalls = new AtomicInteger();
        private final AtomicInteger requestDeltaCalls = new AtomicInteger();

        @Override
        public void baselineCut() {
            baselineCutCalls.incrementAndGet();
        }

        @Override
        public ExecutionDataStore requestDelta(String traceId) {
            requestDeltaCalls.incrementAndGet();
            return new ExecutionDataStore();
        }
    }

    private static SutHandle fakeSut() {
        return new SutHandle() {
            @Override
            public String baseUri() {
                return "http://localhost:" + port;
            }

            @Override
            public String readLog() {
                return "";
            }

            @Override
            public long logOffset() {
                return 0;
            }

            @Override
            public String readLogFrom(long offset) {
                return "";
            }

            @Override
            public String readLogRange(long start, long end) {
                return "";
            }

            @Override
            public void stop() {
                // no-op
            }
        };
    }

    @Test
    @DisplayName("REQ-015: trial invoke는 SQL scope를 열지 않고 커버리지 dump를 스킵하며 산출물에 흔적을 남기지 않는다")
    void trialInvokeSkipsCaptureAndCoverage() throws Exception {
        RecordingSqlCaptureBackend sqlCapture = new RecordingSqlCaptureBackend();
        RecordingCoverageProbe coverage = new RecordingCoverageProbe();

        EndpointExplorationRunner runner = new EndpointExplorationRunner(
                fakeSut(), /* connection */ null, /* dbType */ null,
                coverage, /* analyzer */ null, /* budgetRequests */ 0,
                /* httpCapture */ null, List.of(), List.of(),
                /* authProvider */ null, /* authConfig */ null,
                Map.of(), Map.of(),
                RequestHeaders.empty(), sqlCapture, /* kafkaCapture */ null);

        Endpoint endpoint = new Endpoint("get-items", "GET", "/items", "ItemController", "get",
                List.of(), false);

        InvocationOutcome outcome = runner.invokeTrial(endpoint, Json.mapper().createObjectNode());

        // 실제로 SUT가 호출되고 응답이 정상 반환됨(trial 자체는 동작).
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.response().path("ok").asBoolean()).isTrue();

        // SQL scope 미개설(REQ-015).
        assertThat(sqlCapture.beginCalls.get()).isZero();
        assertThat(outcome.capturedSql()).isEmpty();

        // 요청별 JaCoCo dump 스킵(REQ-015) — baselineCut/requestDelta 모두 미호출.
        assertThat(coverage.baselineCutCalls.get()).isZero();
        assertThat(coverage.requestDeltaCalls.get()).isZero();
        assertThat(outcome.coveredBranches()).isEmpty();

        // 교환(egress/http exchange)도 산출물에 미반영.
        assertThat(outcome.httpExchanges()).isEmpty();
        assertThat(outcome.egressCalls()).isEmpty();
    }

    @Test
    @DisplayName("REQ-015: trial invoke를 여러 번 반복해도 캡처-off 배선은 매번 동일(누적 부작용 없음)")
    void trialInvokeRepeatedCallsStayCaptureOff() throws Exception {
        RecordingSqlCaptureBackend sqlCapture = new RecordingSqlCaptureBackend();
        RecordingCoverageProbe coverage = new RecordingCoverageProbe();

        EndpointExplorationRunner runner = new EndpointExplorationRunner(
                fakeSut(), null, null,
                coverage, null, 0,
                null, List.of(), List.of(),
                null, null,
                Map.of(), Map.of(),
                RequestHeaders.empty(), sqlCapture, null);

        Endpoint endpoint = new Endpoint("get-items-2", "GET", "/items", "ItemController", "get",
                List.of(), false);

        for (int i = 0; i < 3; i++) {
            InvocationOutcome outcome = runner.invokeTrial(endpoint, Json.mapper().createObjectNode());
            assertThat(outcome.status()).isEqualTo(200);
        }

        assertThat(sqlCapture.beginCalls.get()).isZero();
        assertThat(coverage.baselineCutCalls.get()).isZero();
        assertThat(coverage.requestDeltaCalls.get()).isZero();
    }
}
