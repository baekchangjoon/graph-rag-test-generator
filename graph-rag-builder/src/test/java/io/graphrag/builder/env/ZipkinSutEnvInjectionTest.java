package io.graphrag.builder.env;

import io.graphrag.builder.capture.zipkin.ZipkinSpanReceiver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZipkinSutEnvInjectionTest {

    @Test
    @DisplayName("REQ-008/006: sleuth env has zipkin endpoint, no sampler override")
    void env() {
        var r = new ZipkinSpanReceiver();
        r.start();
        try {
            Map<String, String> env = AnalysisEnvironment.sleuthZipkinEnv(r);
            assertThat(env).containsEntry("SPRING_ZIPKIN_SENDER_TYPE", "web");
            assertThat(env.get("SPRING_ZIPKIN_BASEURL")).isEqualTo(r.endpoint());
            assertThat(env).doesNotContainKey("SPRING_SLEUTH_SAMPLER_PROBABILITY");
        } finally {
            r.close();
        }
    }
}
