package io.graphrag.testlib.noop;

import io.graphrag.model.DashboardEvent;
import io.graphrag.model.DashboardEventType;
import io.graphrag.testlib.api.AuthClient;
import io.graphrag.testlib.api.DashboardReporter;
import io.graphrag.testlib.api.HttpMockClient;
import io.graphrag.testlib.api.SocketMockClient;
import io.graphrag.testlib.scope.Config;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class NoopAdaptersTest {

    private final Config emptyConfig = Config.from(Map.of());

    @Test
    void noopDashboardReporterAcceptsEventsSilently() {
        DashboardReporter rep = new NoopDashboardReporter();
        DashboardEvent ev = new DashboardEvent(
                UUID.randomUUID(),
                DashboardEventType.SCOPE_CREATED,
                "t-1", Instant.now(), null);

        assertThatNoException().isThrownBy(() -> rep.report(ev));
    }

    @Test
    void noopHttpMockClientStubAndRemoveDoNotThrow() {
        HttpMockClient http = new NoopHttpMockClient();
        assertThatNoException().isThrownBy(() ->
                http.stub("/anything")
                        .method("POST")
                        .withHeader("baggage", "x")
                        .withBaggage("test-id", "t")
                        .respondJson("{}")
                        .respondStatus(200)
                        .register());
        assertThatNoException().isThrownBy(() -> http.removeAllForScope("t"));
    }

    @Test
    void noopSocketMockClientBindAndRemoveDoNotThrow() {
        SocketMockClient socket = new NoopSocketMockClient();
        assertThatNoException().isThrownBy(() ->
                socket.bind("host", 9000)
                        .withSessionField("session", "x")
                        .onReceiveHex("01 02")
                        .respondHex("00")
                        .step(1)
                        .register());
        assertThatNoException().isThrownBy(() -> socket.removeSession("t"));
    }

    @Test
    void noopAuthClientReturnsBlankToken() {
        AuthClient auth = new NoopAuthClient();
        AuthClient.Token t = auth.login("u", "p");

        assertThat(t.raw()).isNotNull();
        assertThat(t.bearerHeader()).isEqualTo("Bearer " + t.raw());
    }

    @Test
    void adaptersExposedViaSpiFactories() {
        assertThat(new NoopDashboardAdapter().create(emptyConfig))
                .isInstanceOf(NoopDashboardReporter.class);
        assertThat(new NoopHttpMockAdapter().create(emptyConfig))
                .isInstanceOf(NoopHttpMockClient.class);
        assertThat(new NoopSocketMockAdapter().create(emptyConfig))
                .isInstanceOf(NoopSocketMockClient.class);
        assertThat(new NoopAuthAdapter().create(emptyConfig))
                .isInstanceOf(NoopAuthClient.class);

        assertThat(new NoopDashboardAdapter().name()).isEqualTo("noop");
    }
}
