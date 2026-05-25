package io.graphrag.dashboard.leak;

import io.graphrag.dashboard.domain.TestRunState;

/**
 * 누수 감지 시 알림을 받는 채널. 구현체는 Spring 빈으로 자동 발견.
 */
public interface AlertChannel {
    void onLeaked(TestRunState run);
}
