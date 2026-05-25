package io.graphrag.testlib.noop;

import io.graphrag.testlib.api.SocketMockClient;

import java.util.UUID;

/**
 * 외부 socket 의존이 없는 환경을 위한 no-op 구현.
 */
public final class NoopSocketMockClient implements SocketMockClient {

    @Override
    public Session bind(String host, int port) {
        return new NoopSession();
    }

    @Override
    public void removeSession(String testId) { /* nothing */ }

    private static final class NoopSession implements Session {
        @Override public Session withSessionField(String name, String value) { return this; }
        @Override public Session onReceive(byte[] pattern) { return this; }
        @Override public Session onReceiveHex(String hex) { return this; }
        @Override public Session respond(byte[] bytes) { return this; }
        @Override public Session respondHex(String hex) { return this; }
        @Override public Session step(int order) { return this; }
        @Override public String register() { return "noop-" + UUID.randomUUID(); }
    }
}
