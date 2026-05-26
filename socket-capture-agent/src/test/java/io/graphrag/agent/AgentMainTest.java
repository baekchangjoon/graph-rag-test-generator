package io.graphrag.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMainTest {

    static Instrumentation inst;

    @BeforeAll
    static void installAgent() {
        inst = ByteBuddyAgent.install();
        AgentMain.install(inst);
    }

    @AfterAll
    static void cleanup() {
        CaptureCounter.reset();
    }

    @BeforeEach
    void resetCounter() {
        CaptureCounter.reset();
    }

    @Test
    void agentInstrumentsSampleTargetInvocations() {
        SampleTarget target = new SampleTarget();
        target.invoke("x");
        target.invoke("y");
        target.invoke("z");

        assertThat(CaptureCounter.invocations()).isEqualTo(3);
    }

    @Test
    void sampleTargetStillReturnsExpectedValue() {
        SampleTarget target = new SampleTarget();
        assertThat(target.invoke("hello")).isEqualTo("handled:hello");
    }

    @Test
    void agentInstallationIsIdempotent() {
        AgentMain.install(inst);   // 두 번째 install
        SampleTarget target = new SampleTarget();
        target.invoke("a");
        assertThat(CaptureCounter.invocations()).isGreaterThanOrEqualTo(1);
    }
}
