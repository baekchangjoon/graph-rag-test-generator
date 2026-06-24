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
 *
 * <p>병렬 실행(parallelAware=true)에서는 log-parser 폴백을 비활성화한다. 폴백은
 * timestamp-window 기반으로 동시 워커 로그가 섞이면 교차 오염이 발생하기 때문이다.
 * OTLP span은 traceId로 정확하게 귀속되므로 병렬에서 otel 결과가 빈 경우 빈 결과를 반환한다.
 *
 * <p>await 타임아웃 기본값은 순차·병렬 공통 {@value #AWAIT_TIMEOUT_MILLIS}ms이다. (이전에는 병렬
 * OTLP 지연을 의심해 병렬 기본값을 30s로 늘렸으나, 진짜 원인은 pjacoco includes 과대범위로 인한
 * OTel export starve였고 그 근본수정 후 span이 ~100ms 내 도착하므로 8s로 되돌렸다 — 설계 §9-B.)
 * 진짜 부하 spike가 있는 SUT는 {@code --sql-await-ms} CLI 플래그로 재정의한다.
 */
public final class OtelSpanCapture implements SqlCaptureBackend {

    private static final Logger log = LoggerFactory.getLogger(OtelSpanCapture.class);

    /** PoC①에서 확정: db.query.parameter.N의 N이 0-based. */
    static final int PARAM_INDEX_BASE = 0;

    private static final String PARAM_PREFIX = "db.query.parameter.";
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d+");

    /** 순차·병렬 공통 기본 await 타임아웃. 근본수정(§9-B) 후 span은 ~100ms 내 도착하므로 8s면 충분. */
    static final long AWAIT_TIMEOUT_MILLIS = 8_000;
    // drain은 "마지막 span 후 QUIESCENCE_MILLIS 동안 새 span 없음"으로 완료를 판정한다. 이 값은
    // 요청당 drain의 고정 floor(프로파일링: sqlDrain ≈ 마지막-span-시간 + QUIESCENCE, 균일 분포).
    // OTEL agent BSP_SCHEDULE_DELAY=100ms(배치 export) → 안전하려면 배치 간격보다 커야 한다.
    // 150 = 배치 간격(100ms)의 1.5×(jitter 마진). 250→150으로 요청당 ~100ms 단축(OTEL 풀빌드 가속).
    // 100은 BSP 경계라 부하 높은 CI에서 늦은 span 누락(flaky) 위험이 있어 채택하지 않음.
    static final long QUIESCENCE_MILLIS = 150;
    // attach 모드: 컨테이너→호스트 OTLP가 Docker Desktop VM hop을 거쳐 BSP 배치 export 지연·jitter가 커진다.
    // 빠른 요청의 db span이 기본 창을 넘겨 도착해 log-parser로 폴백되는 tail-latency race를 줄이려 창을 넓힌다.
    // attach는 correctness e2e 경로라 요청당 수백 ms 여유는 허용된다. analysis(로컬 프로세스 SUT) 및 P2-5
    // 병렬 게이트는 150ms 그대로 → timing-equivalence 무영향. (250ms로는 잔여 flake가 남아 500ms로 상향.)
    public static final long ATTACH_QUIESCENCE_MILLIS = 500;
    private static final long POLL_MILLIS = 50;

    private final OtlpTraceReceiver receiver;
    private final SutHandle sut;
    private final TraceParent traceParent;
    /**
     * true이면 병렬 실행 경로: log-parser 폴백을 비활성화한다.
     * 폴백은 timestamp-window 기반으로 동시 워커 로그가 섞이면 교차 오염이 발생한다.
     */
    private final boolean parallelAware;
    /**
     * drain()의 OTLP entry-span await 타임아웃 (ms). 0이면 기본값 {@value #AWAIT_TIMEOUT_MILLIS}ms 적용.
     * {@code --sql-await-ms}(BuildConfig.sqlAwaitMs)로 양수 재정의 가능.
     */
    private final long awaitTimeoutMillis;
    /** drain 완료 판정용 quiescence 창 (ms). 0이면 {@value #QUIESCENCE_MILLIS}ms. attach는 더 넓힌다. */
    private final long quiescenceMillis;

    /** 순차(parallelism=1) 호환 생성자 — log-parser 폴백 활성, 기본 타임아웃. */
    public OtelSpanCapture(OtlpTraceReceiver receiver, SutHandle sut, TraceParent traceParent) {
        this(receiver, sut, traceParent, false, 0L);
    }

    /** parallelAware=true이면 병렬 경로: log-parser 폴백 비활성. 기본 타임아웃 사용. */
    public OtelSpanCapture(OtlpTraceReceiver receiver, SutHandle sut, TraceParent traceParent,
                           boolean parallelAware) {
        this(receiver, sut, traceParent, parallelAware, 0L);
    }

    /**
     * @param awaitTimeoutMillis 0이면 모드별 기본값(순차 8s/병렬 30s), 양수이면 그 값을 사용.
     */
    public OtelSpanCapture(OtlpTraceReceiver receiver, SutHandle sut, TraceParent traceParent,
                           boolean parallelAware, long awaitTimeoutMillis) {
        this(receiver, sut, traceParent, parallelAware, awaitTimeoutMillis, 0L);
    }

    /**
     * 풀 생성자.
     *
     * @param awaitTimeoutMillis 0이면 모드별 기본값(순차 8s/병렬 30s), 양수이면 그 값을 사용.
     * @param quiescenceMillis   0이면 기본 {@value #QUIESCENCE_MILLIS}ms, 양수이면 그 값(attach는 더 넓힘).
     */
    public OtelSpanCapture(OtlpTraceReceiver receiver, SutHandle sut, TraceParent traceParent,
                           boolean parallelAware, long awaitTimeoutMillis, long quiescenceMillis) {
        this.receiver = receiver;
        this.sut = sut;
        this.traceParent = traceParent;
        this.parallelAware = parallelAware;
        this.awaitTimeoutMillis = awaitTimeoutMillis;
        this.quiescenceMillis = quiescenceMillis > 0 ? quiescenceMillis : QUIESCENCE_MILLIS;
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

        @Override public List<ParsedSql> drain() {
            long effectiveTimeout = awaitTimeoutMillis > 0 ? awaitTimeoutMillis : AWAIT_TIMEOUT_MILLIS;
            return drain(effectiveTimeout);
        }

        @Override public List<ParsedSql> drain(long timeoutMillis) {
            try {
                boolean arrived = receiver.awaitEntrySpan(ids.traceId(), ids.spanId(), timeoutMillis);
                if (arrived) {
                    waitForQuiescence(ids.traceId(), timeoutMillis);
                    List<ParsedSql> sql = toParsedSql(receiver.spans(ids.traceId()));
                    if (!sql.isEmpty()) {
                        return sql;
                    }
                }
                // 병렬 경로에서는 log-parser 폴백을 비활성화한다.
                // timestamp-window 폴백은 동시 워커 로그가 섞이면 교차 오염이 발생한다(P2-5 F1).
                // OTLP span이 없으면(0 db spans 또는 timeout) 빈 결과를 반환한다 — 오염 SQL보다 낫다.
                if (parallelAware) {
                    if (!arrived) {
                        log.warn("otel entry span timeout (trace={}) in parallel mode; "
                                + "skipping log-parser fallback to avoid cross-worker contamination",
                                ids.traceId());
                    } else {
                        log.debug("otel yielded 0 db spans (trace={}) in parallel mode; "
                                + "returning empty (no log-parser fallback)", ids.traceId());
                    }
                    return List.of();
                }
                List<ParsedSql> fallback = SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
                if (!arrived) {
                    log.warn("otel entry span timeout (trace={}), fell back to log-parser ({} sql)",
                            ids.traceId(), fallback.size());
                } else if (!fallback.isEmpty()) {
                    // entry span은 왔는데 OTEL DB span이 0이고 로그엔 SQL이 있다 → OTEL 캡처 오설정 신호
                    // (semconv 키 불일치/batch collapse 등). 요청이 실제로 SQL이 없으면(403 등) 둘 다 비어 무경고.
                    log.warn("otel yielded 0 db spans but log-parser found {} (trace={}); "
                            + "OTEL capture may be misconfigured", fallback.size(), ids.traceId());
                }
                return fallback;
            } finally {
                receiver.remove(ids.traceId());
            }
        }

        private void waitForQuiescence(String traceId, long budgetMillis) {
            long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
            while (System.nanoTime() < deadline && !receiver.isQuiescent(traceId, quiescenceMillis)) {
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
                if (k.startsWith(PARAM_PREFIX)) {
                    // 정수 인덱스 suffix만 바인딩으로. 비정수(예: 향후 db.query.parameter.count)는 건너뛴다
                    // (parseInt가 drain 전체를 깨뜨리지 않도록 — 리시버의 방어적 입력 처리와 일관).
                    String suffix = k.substring(PARAM_PREFIX.length());
                    if (DIGITS.matcher(suffix).matches()) {
                        ordered.put(Integer.parseInt(suffix), v);
                    }
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
