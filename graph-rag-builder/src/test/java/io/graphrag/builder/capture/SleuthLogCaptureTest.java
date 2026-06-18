package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SleuthLogCaptureTest {

    /** 비동기로 자라는 수집 로그를 흉내내는 SutHandle. setLog로 내용 교체. */
    private static final class GrowingSut implements SutHandle {
        final AtomicReference<String> log = new AtomicReference<>("");
        void setLog(String s) { log.set(s); }
        public String baseUri() { return ""; }
        public long logOffset() { return log.get().getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
        public String readLog() { return log.get(); }
        public String readLogFrom(long o) { return readLogRange(o, logOffset()); }
        public String readLogRange(long start, long end) {
            byte[] b = log.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int s = (int) Math.min(start, b.length), e = (int) Math.min(end, b.length);
            return new String(b, s, Math.max(0, e - s), java.nio.charset.StandardCharsets.UTF_8);
        }
        public void stop() { }
    }

    // spanId 자리는 hex여야 SLEUTH_BRACKET이 매칭한다(임의 16-hex 사용).
    private static final String SPAN = "9a2b3c4d5e6f7081";
    private static String h5Sql(String trace, String sql) {
        return "x DEBUG 1 --- [order-svc," + trace + "," + SPAN + "] [c-1] org.hibernate.SQL : " + sql;
    }
    private static String h5Bind(String trace, int pos, String val) {
        return "x TRACE 1 --- [order-svc," + trace + "," + SPAN + "] [c-1] "
                + "o.h.type.descriptor.sql.BasicBinder : binding parameter [" + pos + "] as [VARCHAR] - [" + val + "]";
    }

    @Test
    void drain_returnsOnlyMatchingTraceLinesInOrder_excludingInfraAndOtherRequests() throws Exception {
        GrowingSut sut = new GrowingSut();
        // 첫 요청 trace를 결정적으로 얻기
        B3TraceId.Ids ids = new B3TraceId("run-1", "n").next();
        String mine = ids.traceId();
        String other = "ffffffffffffffffffffffffffffffff";

        SleuthLogCapture capture = new SleuthLogCapture(sut, new B3TraceId("run-1", "n"));
        SqlCaptureBackend.Scope scope = capture.begin();
        assertThat(scope.requestHeaders()).containsEntry("X-B3-TraceId", mine);

        // 비동기로 로그가 채워진다: 인프라(trace 없음) + 타 요청(other) + 내 요청(mine) 인터리브
        Thread writer = new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("x DEBUG 1 --- [relay] org.hibernate.SQL : select id from message where state='PUBLISHED'\n");
            sut.setLog(sb.toString());
            sleep(60);
            sb.append(h5Sql(other, "select 1 from dual")).append("\n");
            sut.setLog(sb.toString());
            sleep(60);
            sb.append(h5Sql(mine, "insert into order_events (type) values (?)")).append("\n");
            sb.append(h5Bind(mine, 1, "CREATED")).append("\n");
            sut.setLog(sb.toString());
        });
        writer.start();

        List<ParsedSql> drained = scope.drain();
        writer.join();

        assertThat(drained).extracting(ParsedSql::sql)
                .containsExactly("insert into order_events (type) values (?)");
        assertThat(drained.get(0).bindings())
                .containsExactly(new ParsedSql.Binding(1, "CREATED"));
    }

    @Test
    void drain_waitsForLateArrivingBindLineWithinQuiescenceWindow() {
        // 스펙 §10(Sonnet I8): 첫 일치가 일찍 와도 quiescence 창 내 추가 라인까지 기다려 둘 다 반환.
        // wall-clock 경쟁(flaky) 대신 read-count 기반 결정적 fake: 첫 readLogRange는 SQL-only,
        // 이후엔 SQL+bind → drain이 1→2 증가를 관측해 quietUntil 리셋 경로를 반드시 탄다.
        String mine = new B3TraceId("run-2", "n").next().traceId();
        String sqlOnly = h5Sql(mine, "insert into order_events (type) values (?)") + "\n";
        String sqlAndBind = sqlOnly + h5Bind(mine, 1, "CREATED") + "\n";
        SutHandle sut = new SutHandle() {
            final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            private String current() { return reads.getAndIncrement() == 0 ? sqlOnly : sqlAndBind; }
            public String baseUri() { return ""; }
            public long logOffset() { return 0; }
            public String readLog() { return current(); }
            public String readLogFrom(long o) { return current(); }
            public String readLogRange(long s, long e) { return current(); }
            public void stop() { }
        };
        SleuthLogCapture capture = new SleuthLogCapture(sut, new B3TraceId("run-2", "n"));
        SqlCaptureBackend.Scope scope = capture.begin();

        List<ParsedSql> drained = scope.drain();

        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).bindings())
                .containsExactly(new ParsedSql.Binding(1, "CREATED"));   // 늦게 온 bind까지 포함
    }

    @Test
    void drain_returnsEmptyQuickly_whenNoMatchingLines() {
        GrowingSut sut = new GrowingSut();
        sut.setLog("x DEBUG 1 --- [relay] org.hibernate.SQL : select id from message\n");
        SleuthLogCapture capture = new SleuthLogCapture(sut, new B3TraceId("run-1", "n"));
        SqlCaptureBackend.Scope scope = capture.begin();

        long t0 = System.nanoTime();
        List<ParsedSql> drained = scope.drain();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(drained).isEmpty();
        // 첫 일치가 없으면 FIRST_MATCH_TIMEOUT 부근에서 조기 반환(OVERALL_TIMEOUT까지 안 감)
        assertThat(elapsedMs).isLessThan(SleuthLogCapture.OVERALL_TIMEOUT_MILLIS);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
