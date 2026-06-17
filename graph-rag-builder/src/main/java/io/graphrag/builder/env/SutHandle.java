package io.graphrag.builder.env;

/** SUT 상호작용 표면(탐색 루프가 의존): base URL + SQL 캡처용 로그 슬라이스. SutProcess(분석)와 ContainerSut(attach) 공통. */
public interface SutHandle {
    String baseUri();
    long logOffset();
    String readLog();
    String readLogFrom(long offset);
    String readLogRange(long start, long end);
    void stop();
}
