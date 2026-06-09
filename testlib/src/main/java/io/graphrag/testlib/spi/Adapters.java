package io.graphrag.testlib.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** ServiceLoader + 환경변수 기반 어댑터 선택 (docs/07). */
public final class Adapters {

    private Adapters() {
    }

    public static <T extends Adapter> T select(Class<T> spi, String envVar, String defaultName, Env env) {
        String wanted = env.getOrDefault(envVar, defaultName);
        List<String> available = new ArrayList<>();
        for (T candidate : ServiceLoader.load(spi)) {
            available.add(candidate.name());
            if (candidate.name().equals(wanted)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "no %s adapter named '%s' (available: %s)".formatted(spi.getSimpleName(), wanted, available));
    }
}
