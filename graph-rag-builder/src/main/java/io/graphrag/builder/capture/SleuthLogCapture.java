package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * sleuth 모드 backend: 요청별 유니크 B3 traceId를 주입하고, 수집 로그에서 그 traceId가 박힌 라인만
 * 상관(await→quiescence)해 SqlLogParser로 환원한다. traceId 불일치 = 다른 요청·인프라 폴링 SQL →
 * 자동 배제(denylist 불필요). 비동기(A→B→C, Tram) SQL을 요청 단위로 회수한다.
 */
public final class SleuthLogCapture implements SqlCaptureBackend {

    private static final Logger log = LoggerFactory.getLogger(SleuthLogCapture.class);

    /** 첫 일치 대기(없으면 SQL 없는 요청으로 보고 즉시 빈 결과). */
    static final long FIRST_MATCH_TIMEOUT_MILLIS = 3_000;
    /** drain 전체 상한(Tram 비동기 지연 고려). 도달 시 경고 + 빈 결과. */
    static final long OVERALL_TIMEOUT_MILLIS = 15_000;
    /** 마지막 일치 이후 이 시간 동안 추가 일치가 없으면 완료로 간주. */
    static final long QUIESCENCE_MILLIS = 300;
    static final long POLL_MILLIS = 50;

    private final SutHandle sut;
    private final B3TraceId b3;

    public SleuthLogCapture(SutHandle sut, B3TraceId b3) {
        this.sut = sut;
        this.b3 = b3;
    }

    @Override
    public Scope begin() {
        B3TraceId.Ids ids = b3.next();
        long logStart = sut.logOffset();
        return new SleuthScope(ids, logStart);
    }

    private final class SleuthScope implements Scope {
        private final B3TraceId.Ids ids;
        private final long logStart;

        SleuthScope(B3TraceId.Ids ids, long logStart) {
            this.ids = ids;
            this.logStart = logStart;
        }

        @Override public Map<String, String> requestHeaders() {
            return ids.headers();
        }

        @Override public List<ParsedSql> drain() {
            return drain(OVERALL_TIMEOUT_MILLIS);
        }

        @Override public List<ParsedSql> drain(long timeoutMillis) {
            long startNanos = System.nanoTime();
            long overallDeadline = startNanos + timeoutMillis * 1_000_000L;
            // caller가 더 짧은 timeout을 주면(예: KafkaCaptureRunner의 VARIANT_SETTLE_MILLIS) 그것을 존중.
            long firstMatchDeadline = Math.min(overallDeadline,
                    startNanos + FIRST_MATCH_TIMEOUT_MILLIS * 1_000_000L);

            int matchCount = matchingLines().size();
            // (1) 첫 일치 대기
            while (matchCount == 0 && System.nanoTime() < firstMatchDeadline) {
                sleep(POLL_MILLIS);
                matchCount = matchingLines().size();
            }
            if (matchCount == 0) {
                log.debug("sleuth: no SQL lines for trace {} within first-match window (empty result)",
                        ids.traceId());
                return List.of();
            }
            // (2) quiescence: 추가 일치가 멈출 때까지
            long quietUntil = System.nanoTime() + QUIESCENCE_MILLIS * 1_000_000L;
            while (System.nanoTime() < overallDeadline) {
                sleep(POLL_MILLIS);
                int now = matchingLines().size();
                if (now > matchCount) {
                    matchCount = now;
                    quietUntil = System.nanoTime() + QUIESCENCE_MILLIS * 1_000_000L;
                } else if (System.nanoTime() >= quietUntil) {
                    return SqlLogParser.parse(String.join("\n", matchingLines()));
                }
            }
            // (3) overall timeout — 완전성/순서 신뢰 불가 → 경고 + 빈 결과
            log.warn("sleuth: drain timed out before quiescence for trace {} ({} matching line(s) seen); "
                    + "returning empty to avoid partial/misordered capture", ids.traceId(), matchCount);
            return List.of();
        }

        private List<String> matchingLines() {
            // 매 폴링마다 전체 슬라이스를 재스캔(O(n) per poll). 합성 픽스처 DoD에는 충분.
            // 라이브 attach(Spec 1)에서 긴 캡처 시 O(n^2)가 될 수 있어 델타 스캔 최적화는 그때 검토.
            String slice = sut.readLogRange(logStart, sut.logOffset());
            List<String> out = new ArrayList<>();
            for (String line : slice.split("\\R")) {
                String token = SqlLogParser.extractTraceId(line);
                if (token != null && SqlLogParser.traceIdMatches(ids.traceId(), token)) {
                    out.add(line);
                }
            }
            return out;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
