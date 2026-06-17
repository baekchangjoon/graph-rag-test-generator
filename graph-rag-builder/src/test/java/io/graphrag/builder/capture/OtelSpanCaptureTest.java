package io.graphrag.builder.capture;

import io.graphrag.builder.capture.otlp.OtlpTraceReceiver;
import io.graphrag.builder.capture.otlp.SpanRecord;
import io.graphrag.builder.env.SutHandle;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelSpanCaptureTest {

    @Test
    void drain_mapsDbSpansToParsedSqlInStartOrder() {
        OtlpTraceReceiver receiver = new OtlpTraceReceiver();   // start() 호출 안 함
        OtelSpanCapture capture = new OtelSpanCapture(receiver, noopSut(), new TraceParent("run-1"));
        OtelSpanCapture.OtelScope scope = (OtelSpanCapture.OtelScope) capture.begin();
        String tid = scope.traceId();
        String injected = scope.spanId();

        receiver.addForTest(dbSpan(tid, "s1", 200, "update owners set city=? where id=?", "Seoul", "7"));
        receiver.addForTest(dbSpan(tid, "s2", 100, "insert into owners (first_name) values (?)", "Alice"));
        receiver.addForTest(entrySpan(tid, injected, 50));

        List<ParsedSql> sql = scope.drain();
        assertThat(sql).extracting(ParsedSql::sql)
                .containsExactly("insert into owners (first_name) values (?)",
                                 "update owners set city=? where id=?");
        assertThat(sql.get(0).bindings()).extracting(ParsedSql.Binding::value).containsExactly("Alice");
        assertThat(sql.get(0).bindings().get(0).position()).isEqualTo(1);
    }

    /** PoC②: agent 2.16.0이 stable semconv opt-in 없이 쓰는 구 키 db.statement도 읽어야 한다. */
    @Test
    void drain_readsLegacyDbStatementAttribute() {
        OtlpTraceReceiver receiver = new OtlpTraceReceiver();
        OtelSpanCapture capture = new OtelSpanCapture(receiver, noopSut(), new TraceParent("run-legacy"));
        OtelSpanCapture.OtelScope scope = (OtelSpanCapture.OtelScope) capture.begin();
        String tid = scope.traceId();
        String injected = scope.spanId();

        receiver.addForTest(legacyDbSpan(tid, "s1", 100,
                "insert into order_events (type,user_id,id) values (?,?,?)", "CREATED", "poc-user-1", "poc-evt-1"));
        receiver.addForTest(blankDbSpan(tid, "s0", 90));   // 드라이버 잡음(빈 db.statement) — 제외돼야 함
        receiver.addForTest(entrySpan(tid, injected, 50));

        List<ParsedSql> sql = scope.drain();
        assertThat(sql).extracting(ParsedSql::sql)
                .containsExactly("insert into order_events (type,user_id,id) values (?,?,?)");
        assertThat(sql.get(0).bindings()).extracting(ParsedSql.Binding::value)
                .containsExactly("CREATED", "poc-user-1", "poc-evt-1");
    }

    private static SpanRecord dbSpan(String tid, String spanId, long start, String sql, String... params) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("db.query.text", sql);
        for (int i = 0; i < params.length; i++) attrs.put("db.query.parameter." + i, params[i]);
        return new SpanRecord(tid, spanId, "root", "db", "SPAN_KIND_CLIENT", start, attrs, List.of());
    }
    private static SpanRecord legacyDbSpan(String tid, String spanId, long start, String sql, String... params) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("db.statement", sql);
        attrs.put("db.system", "postgresql");
        for (int i = 0; i < params.length; i++) attrs.put("db.query.parameter." + i, params[i]);
        return new SpanRecord(tid, spanId, "root", "db", "SPAN_KIND_CLIENT", start, attrs, List.of());
    }
    private static SpanRecord blankDbSpan(String tid, String spanId, long start) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("db.statement", "");
        attrs.put("db.system", "postgresql");
        return new SpanRecord(tid, spanId, "root", "db", "SPAN_KIND_CLIENT", start, attrs, List.of());
    }
    private static SpanRecord entrySpan(String tid, String injected, long start) {
        return new SpanRecord(tid, "f".repeat(16), injected, "GET /x", "SPAN_KIND_SERVER", start, Map.of(), List.of());
    }
    private static SutHandle noopSut() {
        return new SutHandle() {
            public String baseUri() { return ""; }
            public String readLog() { return ""; }
            public long logOffset() { return 0; }
            public String readLogFrom(long o) { return ""; }
            public String readLogRange(long s, long e) { return ""; }
            public void stop() { }
        };
    }
}
