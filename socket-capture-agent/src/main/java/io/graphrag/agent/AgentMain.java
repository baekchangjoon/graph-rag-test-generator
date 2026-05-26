package io.graphrag.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * Phase 5 raw socket 캡처 javaagent의 진입점.
 *
 * <p>{@code -javaagent:socket-capture-agent.jar} 로 부착하거나, 테스트에서
 * {@code net.bytebuddy.agent.ByteBuddyAgent.install()} 후 {@link #install(Instrumentation)}로
 * 직접 설치할 수 있다.
 *
 * <p>Phase 5 minimum: ByteBuddy 인프라 + {@link CaptureCounter}로 instrumentation이 동작함을
 * 시연. 실제 {@code InputStream/OutputStream} 후킹은 후속.
 */
public final class AgentMain {

    private static volatile boolean installed = false;

    private AgentMain() {}

    public static void premain(String args, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(inst);
    }

    /** Idempotent. 두 번째 호출은 noop. */
    public static synchronized void install(Instrumentation inst) {
        if (installed) return;
        installed = true;
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("io.graphrag.agent.SampleTarget"))
                .transform((builder, type, loader, module, pd) ->
                        builder.visit(Advice.to(SampleAdvice.class)
                                .on(ElementMatchers.named("invoke"))))
                .installOn(inst);
    }

    /** 호출 횟수를 기록하는 Advice. SampleTarget.invoke 호출 전후로 카운터 증가. */
    public static class SampleAdvice {
        @Advice.OnMethodEnter
        public static void onEnter() {
            CaptureCounter.incrementInvocations();
        }
    }
}
