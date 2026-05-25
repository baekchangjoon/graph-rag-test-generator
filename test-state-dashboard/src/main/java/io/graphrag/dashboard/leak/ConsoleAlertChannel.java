package io.graphrag.dashboard.leak;

import io.graphrag.dashboard.domain.TestRunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 기본 알람 채널: 콘솔 로그.
 */
@Component
public class ConsoleAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAlertChannel.class);

    @Override
    public void onLeaked(TestRunState run) {
        log.warn("LEAKED test detected: testId={} class={} method={} startedAt={} dbRows={} httpStubs={} socketSessions={}",
                run.testId(), run.testClass(), run.testMethod(), run.startedAt(),
                run.dbRows().size(), run.httpStubs().size(), run.socketSessions().size());
    }
}
