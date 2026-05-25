package io.graphrag.builder.capture;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 단일 path 분석 중에 캡처된 사실을 누적하는 컨텍스트.
 *
 * <p>{@link #current()}는 ThreadLocal로 현재 thread에 활성화된 컨텍스트를 반환.
 * 분석 harness가 path별로 컨텍스트를 set/clear한다.
 */
public final class CaptureContext {

    private static final ThreadLocal<CaptureContext> CURRENT = new ThreadLocal<>();

    private final String pathId;
    private final List<CapturedSql> capturedSql = new ArrayList<>();
    private final List<CapturedHttpCall> capturedHttpCalls = new ArrayList<>();

    public CaptureContext(String pathId) {
        this.pathId = Objects.requireNonNull(pathId, "pathId");
    }

    public String pathId() { return pathId; }

    public synchronized void addCapturedSql(CapturedSql sql) {
        capturedSql.add(sql);
    }

    public synchronized List<CapturedSql> capturedSql() {
        return Collections.unmodifiableList(new ArrayList<>(capturedSql));
    }

    public synchronized void addCapturedHttpCall(CapturedHttpCall call) {
        capturedHttpCalls.add(call);
    }

    public synchronized List<CapturedHttpCall> capturedHttpCalls() {
        return Collections.unmodifiableList(new ArrayList<>(capturedHttpCalls));
    }

    public static CaptureContext current() { return CURRENT.get(); }

    public static void set(CaptureContext context) { CURRENT.set(context); }

    public static void clear() { CURRENT.remove(); }
}
