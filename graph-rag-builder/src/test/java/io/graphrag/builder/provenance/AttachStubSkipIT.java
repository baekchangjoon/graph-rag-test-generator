package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.InvocationOutcome;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15 attach 안전 게이트 — REQ-025: attach 모드에서는 후보 {@code stubs.json}이 비어있지 않아도
 * {@link TrialRunner}가 stub 등록을 전혀 시도하지 않는다(attach WireMock 라우팅은 Phase C 소관).
 * {@link HttpCaptureServer#registerStub}을 오버라이드한 fake로 실제 WireMock 기동 없이 "호출 여부"만
 * 관측한다({@code start()}를 호출하지 않으므로 내부 {@code server} 필드는 null로 남지만, 이 테스트
 * 경로에서는 attach 게이트가 그 필드에 접근하기 전에 이미 skip해야 하므로 문제되지 않는다 — 만약
 * 회귀로 실제 등록을 시도한다면 NPE로 즉시 드러난다).
 */
class AttachStubSkipIT {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    @TempDir
    Path tempDir;

    private static final Endpoint ENDPOINT =
            new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);

    private Connection newH2Connection() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:attach-stub-skip-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT)");
        }
        return connection;
    }

    private static class FixedLogSutHandle implements SutHandle {
        @Override public String baseUri() { return "http://fake"; }
        @Override public long logOffset() { return 0; }
        @Override public String readLog() { return ""; }
        @Override public String readLogFrom(long offset) { return ""; }
        @Override public String readLogRange(long start, long end) { return ""; }
        @Override public void stop() { }
    }

    /** REQ-025 관측용 fake — registerStub/removeStub 호출 여부만 기록하고 실제 WireMock에 위임하지 않는다. */
    private static class RecordingHttpCaptureServer extends HttpCaptureServer {
        boolean registerStubCalled = false;
        boolean removeStubCalled = false;

        @Override
        public void registerStub(StubMapping mapping) {
            registerStubCalled = true;
        }

        @Override
        public void removeStub(UUID id) {
            removeStubCalled = true;
        }
    }

    @Test
    @DisplayName("REQ-025: attach 모드에서는 비어있지 않은 stub이 있어도 registerStub이 호출되지 않고 skip된다(사유 로그, invoke는 정상 진행)")
    void req025_attachModeSkipsStubRegistrationEvenWithNonEmptyStub() throws Exception {
        Path candDir = Files.createDirectories(tempDir.resolve("cand-stub-skip-" + DB_SEQ.incrementAndGet()));
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("amount", 500);
        Files.writeString(candDir.resolve("body.json"), body.toString());
        Files.writeString(candDir.resolve("seed.sql"), "INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);");
        // 비어있지 않은(실 request/response를 가진) stub — 비-attach 경로였다면 등록됐을 내용.
        Files.writeString(candDir.resolve("stubs.json"),
                "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/fraud/check\"},"
                        + "\"response\":{\"status\":200,\"jsonBody\":{\"status\":\"CLEAR\"}}}");

        RecordingHttpCaptureServer fakeHttpCapture = new RecordingHttpCaptureServer();
        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, b) -> new InvocationOutcome(
                    200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, fakeHttpCapture,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(), fakeInvoker,
                    /* attachMode */ true, /* allowSeedFlag */ true, /* confirmNonProductionFlag */ true);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(),
                    new ProvenanceReport("post-api-transfers", List.of(), List.of(), List.of()));

            assertThat(outcome.promoted())
                    .as("stub 등록 skip과 무관하게 invoke 자체는 정상 진행되어야 한다")
                    .isTrue();
            assertThat(fakeHttpCapture.registerStubCalled)
                    .as("REQ-025: attach 모드에서는 stub이 비어있지 않아도 registerStub을 호출하면 안 된다")
                    .isFalse();
            assertThat(fakeHttpCapture.removeStubCalled)
                    .as("애초에 등록하지 않았으므로 finally의 정리(removeStub)도 호출되지 않아야 한다")
                    .isFalse();
        }
    }

    /** 비-attach 회귀: attachMode=false면 같은 non-empty stub이 정상 등록·정리된다(기존 TrialDigestIT 동등 검증). */
    @Test
    @DisplayName("회귀: attachMode=false면 non-empty stub이 정상 등록되고 trial 종료 시 정리된다")
    void nonAttachModeRegressionStubStillRegisteredAndCleanedUp() throws Exception {
        Path candDir = Files.createDirectories(tempDir.resolve("cand-stub-nonattach-" + DB_SEQ.incrementAndGet()));
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("amount", 500);
        Files.writeString(candDir.resolve("body.json"), body.toString());
        Files.writeString(candDir.resolve("seed.sql"), "INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);");
        Files.writeString(candDir.resolve("stubs.json"),
                "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/fraud/check\"},"
                        + "\"response\":{\"status\":200,\"jsonBody\":{\"status\":\"CLEAR\"}}}");

        RecordingHttpCaptureServer fakeHttpCapture = new RecordingHttpCaptureServer();
        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, b) -> new InvocationOutcome(
                    200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, fakeHttpCapture,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(), fakeInvoker);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(),
                    new ProvenanceReport("post-api-transfers", List.of(), List.of(), List.of()));

            assertThat(outcome.promoted()).isTrue();
            assertThat(fakeHttpCapture.registerStubCalled).isTrue();
            assertThat(fakeHttpCapture.removeStubCalled).isTrue();
        }
    }
}
