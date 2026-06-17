package io.graphrag.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 탐색/로그인/생성-테스트 요청에 주입할 커스텀 헤더(값은 HeaderTemplate). onLogin=로그인 호출에도 적용 여부(입력). */
public final class RequestHeaders {

    private final Map<String, String> entries;   // name → value template
    private final boolean onLogin;

    private RequestHeaders(Map<String, String> entries, boolean onLogin) {
        this.entries = entries;
        this.onLogin = onLogin;
    }

    public static RequestHeaders empty() { return new RequestHeaders(Map.of(), false); }

    public static RequestHeaders parse(List<String> lines, boolean onLogin) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) { continue; }
            int colon = t.indexOf(':');
            if (colon < 0) { throw new IllegalArgumentException("헤더 형식 오류('Name: value'): " + line); }
            String value = t.substring(colon + 1).strip();
            HeaderTemplate.validate(value);   // fail-fast: 잘못된 {{now:pattern}} 은 시작 시 거부
            map.put(t.substring(0, colon).strip(), value);
        }
        return new RequestHeaders(map, onLogin);
    }

    public Map<String, String> entries() { return entries; }
    public boolean onLogin() { return onLogin; }
    public boolean isEmpty() { return entries.isEmpty(); }

    /** 이 요청 시각으로 모든 값 템플릿을 전개. */
    public Map<String, String> resolved(Instant now) {
        Map<String, String> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> out.put(k, HeaderTemplate.resolve(v, now)));
        return out;
    }
}
