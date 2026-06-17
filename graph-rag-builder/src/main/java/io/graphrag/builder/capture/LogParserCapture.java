package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;

import java.util.List;
import java.util.Map;

/** 기존 SqlLogParser + byte-offset 경로를 SqlCaptureBackend 뒤로. OTEL 폴백/기본. */
public final class LogParserCapture implements SqlCaptureBackend {

    /** doSend가 의존하던 150ms 콘솔 flush 여유를 drain 내부로 이동. */
    private static final long SETTLE_MILLIS = 150;

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
                while (System.nanoTime() < deadline) {
                    sleep(SETTLE_MILLIS);
                    List<ParsedSql> sql = SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
                    if (!sql.isEmpty()) {
                        sleep(SETTLE_MILLIS);   // 후속 flush 여유
                        return SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
                    }
                }
                return List.of();
            }
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
