package io.graphrag.builder.capture.responsefields;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseFieldReadTrackerTest {

    @AfterEach
    void cleanup() {
        ResponseFieldReadTracker.clear();
    }

    @Test
    void currentIsNullWhenNotActivated() {
        assertThat(ResponseFieldReadTracker.current()).isNull();
    }

    @Test
    void activateThenRecordCollectsPaths() throws Exception {
        try (AutoCloseable ignored = ResponseFieldReadTracker.activate()) {
            ResponseFieldReadTracker.recordOnCurrent("available");
            ResponseFieldReadTracker.recordOnCurrent("price.amount");
            ResponseFieldReadTracker.recordOnCurrent("available");   // 중복

            ResponseFieldReadTracker t = ResponseFieldReadTracker.current();
            assertThat(t).isNotNull();
            assertThat(t.readFields()).containsExactly("available", "price.amount");
        }
        assertThat(ResponseFieldReadTracker.current()).isNull();
    }

    @Test
    void recordOnCurrentWithoutActivationIsSilent() {
        ResponseFieldReadTracker.recordOnCurrent("foo");
        assertThat(ResponseFieldReadTracker.current()).isNull();
    }

    @Test
    void blankPathIsIgnored() {
        ResponseFieldReadTracker t = new ResponseFieldReadTracker();
        t.recordFieldRead("");
        t.recordFieldRead(null);
        t.recordFieldRead("   ");
        assertThat(t.readFields()).isEmpty();
    }
}
