package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogParserCaptureTest {

    /** begin()→drain()이 [logStart, 현재offset) 구간만 파싱한다. */
    @Test
    void drain_parsesOnlyRangeSinceBegin() {
        StringBuilder logBuf = new StringBuilder("noise before\n");
        AtomicLong offset = new AtomicLong(logBuf.toString().getBytes().length);
        SutHandle sut = new FakeSut(logBuf, offset);

        SqlCaptureBackend backend = new LogParserCapture(sut);
        SqlCaptureBackend.Scope scope = backend.begin();   // logStart 캡처

        logBuf.append("org.hibernate.SQL : insert into owners (first_name) values (?)\n");
        logBuf.append("org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [Alice]\n");
        offset.set(logBuf.toString().getBytes().length);

        List<ParsedSql> sql = scope.drain();
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0).sql()).contains("insert into owners");
        assertThat(sql.get(0).bindings()).extracting(ParsedSql.Binding::value).containsExactly("Alice");
    }

    @Test
    void requestHeaders_empty() {
        assertThat(new LogParserCapture(new FakeSut(new StringBuilder(), new AtomicLong()))
                .begin().requestHeaders()).isEmpty();
    }

    /** 테스트용 SutHandle: in-memory 로그 버퍼 + 외부 제어 offset. */
    private static final class FakeSut implements SutHandle {
        private final StringBuilder buf;
        private final AtomicLong offset;
        FakeSut(StringBuilder buf, AtomicLong offset) { this.buf = buf; this.offset = offset; }
        @Override public String baseUri() { return "http://localhost:0"; }
        @Override public String readLog() { return buf.toString(); }
        @Override public long logOffset() { return offset.get(); }
        @Override public String readLogFrom(long o) { return readLogRange(o, Long.MAX_VALUE); }
        @Override public String readLogRange(long start, long end) {
            byte[] b = buf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int from = (int) Math.min(Math.max(start, 0), b.length);
            int to = (int) Math.min(Math.max(end, 0), b.length);
            return from >= to ? "" : new String(b, from, to - from, java.nio.charset.StandardCharsets.UTF_8);
        }
        @Override public void stop() { }
    }
}
