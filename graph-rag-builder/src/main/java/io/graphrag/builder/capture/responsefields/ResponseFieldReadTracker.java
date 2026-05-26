package io.graphrag.builder.capture.responsefields;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 외부 응답 객체에서 SUT가 실제로 읽은 JSON path들을 추적.
 *
 * <p>활용 예: SUT가 외부 HTTP 호출의 응답 객체를 deserialize한 뒤
 * {@code response.getAvailable()}만 접근했다면 path "available"만 기록 → mock 응답에서
 * 다른 필드는 dummy로 채워도 됨 (응답 최소화).
 *
 * <p>본 클래스는 추적 API만 제공. 실제 접근 감지는 두 가지 방식 중 선택:
 * <ol>
 *   <li>Jackson Mixin/Module로 deserialize 시점에 lazy proxy 구성 (Phase 7+)
 *   <li>ByteBuddy javaagent로 getter 메소드 호출 시점 hook (Phase 7+)
 * </ol>
 *
 * <p>현재는 호출자가 명시적으로 {@link #recordFieldRead(String)} 호출.
 */
public final class ResponseFieldReadTracker {

    private static final ThreadLocal<ResponseFieldReadTracker> CURRENT = new ThreadLocal<>();

    private final Set<String> readPaths = Collections.synchronizedSet(new LinkedHashSet<>());

    public static ResponseFieldReadTracker current() { return CURRENT.get(); }

    public static void set(ResponseFieldReadTracker tracker) { CURRENT.set(tracker); }

    public static void clear() { CURRENT.remove(); }

    /** 한 path 추적 시작 — try-with-resources 친화. */
    public static AutoCloseable activate() {
        ResponseFieldReadTracker tracker = new ResponseFieldReadTracker();
        set(tracker);
        return () -> {
            if (current() == tracker) {
                clear();
            }
        };
    }

    /** {@code response.getAvailable()} 같은 접근이 일어났을 때 호출. */
    public void recordFieldRead(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) return;
        readPaths.add(jsonPath);
    }

    /** 활성 tracker가 있으면 거기에 기록. 없으면 noop. */
    public static void recordOnCurrent(String jsonPath) {
        ResponseFieldReadTracker t = current();
        if (t != null) t.recordFieldRead(jsonPath);
    }

    /** 누적된 path 목록 (순서 보존, 중복 제거). */
    public List<String> readFields() {
        synchronized (readPaths) {
            return List.copyOf(readPaths);
        }
    }
}
