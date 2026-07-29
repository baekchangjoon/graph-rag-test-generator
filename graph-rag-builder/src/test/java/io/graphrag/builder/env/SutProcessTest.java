package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SutProcessTest {
    @Test void springApplicationJson_includesBatchSizeZeroWhenRequested() {
        String json = io.graphrag.builder.env.SutProcess.springApplicationJson(java.util.Map.of(), true);
        assertThat(json).contains("\"spring.jpa.properties.hibernate.jdbc.batch_size\":\"0\"");
        assertThat(json).contains("org.hibernate.SQL");
    }

    @Test
    void bootTimeout_defaultsTo90SecondsWhenUnset() {
        assertThat(SutProcess.resolveBootTimeout(null))
                .as("미지정 시 종전 기본값을 유지한다(회귀 0)")
                .isEqualTo(java.time.Duration.ofSeconds(90));
    }

    @Test
    void bootTimeout_honoursOverrideInSeconds() {
        // 부하 있는 머신에서 90초는 재현 불가능한 실패를 만든다(followup
        // 2026-07-29-full-suite-load-flakiness.md). 조정 가능해야 한다.
        assertThat(SutProcess.resolveBootTimeout("300"))
                .isEqualTo(java.time.Duration.ofSeconds(300));
    }

    @Test
    void bootTimeout_ignoresUnusableValuesInsteadOfFailingTheBuild() {
        // 잘못된 값 때문에 빌드가 죽으면 안 된다 — 기본값으로 조용히가 아니라
        // 명시적으로 되돌아간다(호출부가 warn 로그를 남긴다).
        assertThat(SutProcess.resolveBootTimeout("abc")).isEqualTo(java.time.Duration.ofSeconds(90));
        assertThat(SutProcess.resolveBootTimeout("0")).isEqualTo(java.time.Duration.ofSeconds(90));
        assertThat(SutProcess.resolveBootTimeout("-5")).isEqualTo(java.time.Duration.ofSeconds(90));
        assertThat(SutProcess.resolveBootTimeout("  ")).isEqualTo(java.time.Duration.ofSeconds(90));
    }

}
