package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;

import java.util.List;
import java.util.Map;

/** 기존 SqlLogParser + byte-offset 경로를 SqlCaptureBackend 뒤로. OTEL 폴백/기본. */
public final class LogParserCapture implements SqlCaptureBackend {

    /** doSend가 의존하던 150ms 콘솔 flush 여유를 drain 내부로 이동. */
    private static final long SETTLE_MILLIS = 150;
    /** drain(long) quiescence 폴링 간격 / 안정(추가 SQL 없음) 판정 윈도우. */
    private static final long POLL_MILLIS = 100;
    private static final long QUIESCENCE_MILLIS = 500;

    private final SutHandle sut;

    public LogParserCapture(SutHandle sut) {
        this.sut = sut;
    }

    @Override
    public Scope begin() {
        long logStart = sut.logOffset();
        return new Scope() {
            @Override public Map<String, String> requestHeaders() { return Map.of(); }

            @Override public List<ParsedSql> drain() {
                sleep(SETTLE_MILLIS);
                return SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
            }

            @Override public List<ParsedSql> drain(long timeoutMillis) {
                long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
                // (1) 첫 SQL 출현까지 대기.
                int count = 0;
                while (count == 0 && System.nanoTime() < deadline) {
                    sleep(POLL_MILLIS);
                    count = SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset())).size();
                }
                if (count == 0) {
                    return List.of();
                }
                // (2) quiescence: 파싱된 SQL 개수가 안정될 때까지 폴링. 첫 SQL에서 멈추지 않고 늦게 도착하는
                // 후속 SQL(예: dedup SELECT 뒤의 INSERT)까지 포착한다. 개수가 늘면 quiet 윈도우를 리셋.
                long quietUntil = System.nanoTime() + QUIESCENCE_MILLIS * 1_000_000L;
                while (System.nanoTime() < deadline) {
                    sleep(POLL_MILLIS);
                    List<ParsedSql> sql = SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
                    if (sql.size() > count) {
                        count = sql.size();
                        quietUntil = System.nanoTime() + QUIESCENCE_MILLIS * 1_000_000L;
                    } else if (System.nanoTime() >= quietUntil) {
                        return sql;
                    }
                }
                // overall timeout: best-effort로 현재까지 캡처분 반환(count>0이라 비어있지 않음).
                return SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
            }
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
