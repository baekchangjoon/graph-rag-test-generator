package io.graphrag.builder.capture;

import io.graphrag.builder.capture.otlp.OtlpTraceReceiver;
import io.graphrag.builder.capture.otlp.SpanRecord;
import io.graphrag.builder.env.SutHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OTEL DB span을 trace-id로 요청에 귀속하는 1순위 backend.
 * begin()이 요청별 traceparent를 발급, drain()이 entry span 완료 await + quiescence 후
 * 그 trace의 DB span을 ParsedSql로 환원한다. 비면 logStart 기준 log-parser 폴백.
 */
public final class OtelSpanCapture implements SqlCaptureBackend {

    private static final Logger log = LoggerFactory.getLogger(OtelSpanCapture.class);

    /** PoC①에서 확정: db.query.parameter.N의 N이 0-based. */
    static final int PARAM_INDEX_BASE = 0;

    static final long AWAIT_TIMEOUT_MILLIS = 8_000;
    static final long QUIESCENCE_MILLIS = 250;
    private static final long POLL_MILLIS = 50;

    private final OtlpTraceReceiver receiver;
    private final SutHandle sut;
    private final TraceParent traceParent;

    public OtelSpanCapture(OtlpTraceReceiver receiver, SutHandle sut, TraceParent traceParent) {
        this.receiver = receiver;
        this.sut = sut;
        this.traceParent = traceParent;
    }

    @Override
    public Scope begin() {
        TraceParent.Ids ids = traceParent.next();
        long logStart = sut.logOffset();
        return new OtelScope(ids, logStart);
    }

    public final class OtelScope implements Scope {
        private final TraceParent.Ids ids;
        private final long logStart;

        OtelScope(TraceParent.Ids ids, long logStart) {
            this.ids = ids;
            this.logStart = logStart;
        }

        public String traceId() { return ids.traceId(); }
        public String spanId() { return ids.spanId(); }

        @Override public Map<String, String> requestHeaders() {
            return Map.of("traceparent", ids.header());
        }

        @Override public List<ParsedSql> drain() { return drain(AWAIT_TIMEOUT_MILLIS); }

        @Override public List<ParsedSql> drain(long timeoutMillis) {
            try {
                boolean arrived = receiver.awaitEntrySpan(ids.traceId(), ids.spanId(), timeoutMillis);
                if (arrived) {
                    waitForQuiescence(ids.traceId());
                    List<ParsedSql> sql = toParsedSql(receiver.spans(ids.traceId()));
                    if (!sql.isEmpty()) {
                        return sql;
                    }
                }
                List<ParsedSql> fallback = SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
                if (!arrived) {
                    log.warn("otel entry span timeout (trace={}), fell back to log-parser ({} sql)",
                            ids.traceId(), fallback.size());
                }
                return fallback;
            } finally {
                receiver.remove(ids.traceId());
            }
        }

        private void waitForQuiescence(String traceId) {
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadline && !receiver.isQuiescent(traceId, QUIESCENCE_MILLIS)) {
                sleep(POLL_MILLIS);
            }
        }
    }

    /**
     * SQL 텍스트 속성 키. agent 2.16.0은 stable DB semconv opt-in 없이는 구 키 {@code db.statement}로,
     * opt-in 시 신규 키 {@code db.query.text}로 내보낸다(PoC②: order-service/Postgres·PoC①/H2 모두 구 키).
     * 두 컨벤션 모두 지원하도록 신규→구 순으로 읽는다.
     */
    private static final String SQL_TEXT_NEW = "db.query.text";
    private static final String SQL_TEXT_OLD = "db.statement";

    private static String sqlText(Map<String, String> attrs) {
        String sql = attrs.getOrDefault(SQL_TEXT_NEW, attrs.get(SQL_TEXT_OLD));
        return sql == null || sql.isBlank() ? null : sql;
    }

    private static List<ParsedSql> toParsedSql(List<SpanRecord> spans) {
        List<ParsedSql> result = new ArrayList<>();
        List<SpanRecord> dbSpans = new ArrayList<>(spans.stream()
                .filter(s -> sqlText(s.attributes()) != null).toList());
        dbSpans.sort(Comparator.comparingLong(SpanRecord::startUnixNano));
        for (SpanRecord span : dbSpans) {
            String sql = sqlText(span.attributes());
            TreeMap<Integer, String> ordered = new TreeMap<>();
            span.attributes().forEach((k, v) -> {
                if (k.startsWith("db.query.parameter.")) {
                    ordered.put(Integer.parseInt(k.substring("db.query.parameter.".length())), v);
                }
            });
            List<ParsedSql.Binding> bindings = new ArrayList<>();
            ordered.forEach((idx, value) ->
                    bindings.add(new ParsedSql.Binding(idx - PARAM_INDEX_BASE + 1, value)));
            result.add(new ParsedSql(sql, bindings));
        }
        return result;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
