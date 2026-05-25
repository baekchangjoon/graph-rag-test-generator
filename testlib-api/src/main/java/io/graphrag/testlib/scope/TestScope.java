package io.graphrag.testlib.scope;

import io.graphrag.model.DashboardEvent;
import io.graphrag.model.DashboardEventType;
import io.graphrag.model.ResourcesReleased;
import io.graphrag.model.ScopeCleanedPayload;
import io.graphrag.model.ScopeCreatedPayload;
import io.graphrag.testlib.api.AuthClient;
import io.graphrag.testlib.api.DashboardReporter;
import io.graphrag.testlib.api.HttpMockClient;
import io.graphrag.testlib.api.SocketMockClient;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 한 테스트 실행 범위의 자원과 식별자를 보유.
 *
 * <p>생성 시 unique testId 발급, 대시보드에 {@code SCOPE_CREATED} 보고.
 * {@link #cleanup()} 시 어댑터별 자원 해제 + {@code SCOPE_CLEANED} 보고.
 *
 * <p>{@link AutoCloseable} 구현으로 try-with-resources 사용 가능.
 */
public final class TestScope implements AutoCloseable {

    private final String testId;
    private final String testClass;
    private final String testMethod;
    private final String runId;
    private final HttpMockClient http;
    private final SocketMockClient socket;
    private final AuthClient auth;
    private final DashboardReporter dashboard;

    private boolean cleaned = false;

    private TestScope(Builder b) {
        this.testClass = Objects.requireNonNull(b.testClass, "testClass");
        this.testMethod = Objects.requireNonNull(b.testMethod, "testMethod");
        this.runId = Objects.requireNonNull(b.runId, "runId");
        this.dashboard = Objects.requireNonNull(b.dashboard, "dashboard");
        this.http = b.http;
        this.socket = b.socket;
        this.auth = b.auth;
        this.testId = generateTestId(b.namespace);

        dashboard.report(new DashboardEvent(
                UUID.randomUUID(),
                DashboardEventType.SCOPE_CREATED,
                testId,
                Instant.now(),
                new ScopeCreatedPayload(testClass, testMethod, runId)));
    }

    private static String generateTestId(String namespace) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return namespace == null || namespace.isEmpty() ? suffix : namespace + "-" + suffix;
    }

    public String testId() { return testId; }
    public HttpMockClient http() { return http; }
    public SocketMockClient socket() { return socket; }
    public AuthClient auth() { return auth; }

    public synchronized void cleanup() {
        if (cleaned) return;
        cleaned = true;
        int httpReleased = 0;
        int socketReleased = 0;
        if (http != null) {
            http.removeAllForScope(testId);
            httpReleased = 1;   // Phase 0 자원 카운팅은 단순 boolean; resource registry는 후속 task
        }
        if (socket != null) {
            socket.removeSession(testId);
            socketReleased = 1;
        }
        dashboard.report(new DashboardEvent(
                UUID.randomUUID(),
                DashboardEventType.SCOPE_CLEANED,
                testId,
                Instant.now(),
                new ScopeCleanedPayload(new ResourcesReleased(0, httpReleased, socketReleased))));
    }

    @Override
    public void close() { cleanup(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String testClass;
        private String testMethod;
        private String runId;
        private String namespace;
        private HttpMockClient http;
        private SocketMockClient socket;
        private AuthClient auth;
        private DashboardReporter dashboard;

        public Builder testClass(String v) { this.testClass = v; return this; }
        public Builder testMethod(String v) { this.testMethod = v; return this; }
        public Builder runId(String v) { this.runId = v; return this; }
        public Builder namespace(String v) { this.namespace = v; return this; }
        public Builder http(HttpMockClient v) { this.http = v; return this; }
        public Builder socket(SocketMockClient v) { this.socket = v; return this; }
        public Builder auth(AuthClient v) { this.auth = v; return this; }
        public Builder dashboard(DashboardReporter v) { this.dashboard = v; return this; }

        public TestScope build() { return new TestScope(this); }
    }
}
