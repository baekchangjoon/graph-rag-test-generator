package io.graphrag.builder.capture;

import java.util.List;
import java.util.Map;

/**
 * 요청 1건이 유발한 SQL 캡처를 추상화한다. 구현: LogParserCapture(폴백), OtelSpanCapture(1순위).
 * begin()으로 요청 경계를 열고, 호출자는 requestHeaders()를 outbound(HTTP/Kafka)로 주입한 뒤
 * 요청을 보내고, drain()으로 그 요청의 SQL을 순서 보존하여 회수한다.
 */
public interface SqlCaptureBackend {

    Scope begin();

    interface Scope {
        /** 요청에 주입할 상관 헤더 (OTEL: traceparent 1개, log-parser: 빈 맵). transport-agnostic. */
        Map<String, String> requestHeaders();

        /** begin() 이후 SUT가 발행한 SQL + 바인딩 (발행 순서). */
        List<ParsedSql> drain();

        /** drain()을 기대 SQL 출현까지 폴링/await하는 변형 (timeout ms). 폴백/Kafka happy 경로용. */
        default List<ParsedSql> drain(long timeoutMillis) {
            return drain();
        }
    }
}
