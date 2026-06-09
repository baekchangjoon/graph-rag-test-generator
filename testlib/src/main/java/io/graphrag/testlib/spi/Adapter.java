package io.graphrag.testlib.spi;

public interface Adapter {
    /** 환경변수 값과 매칭되는 어댑터 이름 (예: "plain", "noop", "wiremock"). */
    String name();
}
