package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class BuildConfigFlagTest {

    @Test
    void buildConfigHasNoIncrementalAccessor() throws Exception {
        Method m = BuildConfig.class.getMethod("noIncremental");
        assertThat(m.getReturnType()).isEqualTo(boolean.class);
    }
}
