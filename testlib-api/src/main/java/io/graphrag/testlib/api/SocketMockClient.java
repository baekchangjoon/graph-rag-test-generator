package io.graphrag.testlib.api;

/**
 * Socket mock 서비스에 시나리오를 등록하는 어댑터.
 *
 * <p>프로토콜에 session 필드가 있을 때는 {@link Session#withSessionField}로 격리.
 * 없을 때는 도구 2가 직렬 실행 마크 + 인스턴스 분리.
 */
public interface SocketMockClient {

    Session bind(String host, int port);

    void removeSession(String testId);

    interface Session {
        Session withSessionField(String name, String value);
        Session onReceive(byte[] pattern);
        Session onReceiveHex(String hex);
        Session respond(byte[] bytes);
        Session respondHex(String hex);
        /** 다단계 프로토콜의 step 순서. */
        Session step(int order);
        /** 등록 + 발급된 session handle 반환 */
        String register();
    }
}
