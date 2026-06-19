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

    /**
     * REPRO (flaky BuilderIntegrationTest:178 root cause): a consumer emits SQL in stages — dedup
     * existsById SELECTs first, then the INSERT slightly later. drain(timeout) must capture the
     * later INSERT too, not return as soon as the first SQL appears.
     *
     * StagedSut reveals the INSERT only from the 3rd readLogRange call onward. The current
     * drain() does exactly two reads in the "found" branch (detect non-empty, then one fixed
     * 150ms grace re-read), so it returns the SELECTs and DROPS the INSERT — reproducing the
     * race deterministically (no wall-clock dependency). A quiescence-based drain polls until the
     * SQL count stabilises and would capture the INSERT.
     */
    @Test
    void drain_capturesLateInsertEmittedAfterEarlierSelects() {
        String selects =
                "x DEBUG 1 --- [tram-c-1] org.hibernate.SQL : select count(*) from order_events oe1_0 where oe1_0.id=?\n"
              + "x DEBUG 1 --- [tram-c-1] org.hibernate.SQL : select oe1_0.id,oe1_0.type,oe1_0.user_id from order_events oe1_0 where oe1_0.id=?\n";
        String withInsert = selects
              + "x DEBUG 1 --- [tram-c-1] org.hibernate.SQL : insert into order_events (type,user_id,id) values (?,?,?)\n";
        SutHandle sut = new StagedSut(selects, withInsert, 2);   // INSERT visible from the 3rd read

        SqlCaptureBackend.Scope scope = new LogParserCapture(sut).begin();
        List<ParsedSql> sql = scope.drain(8000);

        assertThat(sql).extracting(ParsedSql::kind).contains("INSERT");
        assertThat(sql).filteredOn(s -> "INSERT".equals(s.kind()))
                .anyMatch(s -> "order_events".equals(s.tableName()));
    }

    /**
     * SutHandle modelling the "log slice since begin()": each readLogRange call returns the SQL
     * emitted so far. The late INSERT becomes visible only from the (revealAfterReads+1)-th read,
     * so a drain that stops reading too early misses it. readLogRange ignores start/end (it already
     * returns the post-begin slice) — this avoids coupling visibility to byte offsets.
     */
    private static final class StagedSut implements SutHandle {
        private final String early;
        private final String full;
        private final int revealAfterReads;
        private int reads = 0;
        StagedSut(String early, String full, int revealAfterReads) {
            this.early = early; this.full = full; this.revealAfterReads = revealAfterReads;
        }
        @Override public String baseUri() { return "http://localhost:0"; }
        @Override public String readLog() { return full; }
        @Override public long logOffset() { return 0; }   // logStart=0; readLogRange returns the slice directly
        @Override public String readLogFrom(long o) { return readLogRange(o, Long.MAX_VALUE); }
        @Override public String readLogRange(long start, long end) {
            reads++;
            return reads > revealAfterReads ? full : early;
        }
        @Override public void stop() { }
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
