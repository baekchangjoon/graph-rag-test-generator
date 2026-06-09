package io.graphrag.testlib.api;

/** 외부 socket mock 제어. Phase 0은 noop, Phase 4에서 expectation API 확장. */
public interface SocketMockClient {
    void removeSession(String testId);
}
