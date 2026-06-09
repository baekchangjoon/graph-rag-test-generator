package io.graphrag.testlib.spi;

import java.util.Map;

/** 환경변수 접근 추상화. 테스트에서 주입 가능. */
public final class Env {

    private final Map<String, String> values;

    private Env(Map<String, String> values) {
        this.values = values;
    }

    public static Env fromSystem() {
        return new Env(System.getenv());
    }

    public static Env of(Map<String, String> values) {
        return new Env(values);
    }

    public String get(String name) {
        return values.get(name);
    }

    public String getOrDefault(String name, String defaultValue) {
        String value = values.get(name);
        return value != null ? value : defaultValue;
    }

    /** 필수 변수. 없으면 즉시 실패 (docs/07 fail-fast). */
    public String require(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required environment variable missing: " + name);
        }
        return value;
    }
}
