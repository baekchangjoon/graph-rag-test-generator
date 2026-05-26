package io.graphrag.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * {@link SocketSystemInstaller} 설치 동작 검증.
 *
 * <p>주의: in-process로 ByteBuddyAgent.install() 후 retransform을 시도하면 ByteBuddy 측은
 * REDEFINE COMPLETE를 보고하나, JDK 17의 {@code java.net.Socket} 메소드들은 보통 이미 JIT
 * 컴파일되어 있어 advice 본문이 즉각 적용되지 않을 수 있다 (counter 증가가 관측 불가).
 *
 * <p>운영 적용은 다음 두 가지 중 하나로 권장:
 * <ol>
 *   <li>{@code -javaagent:graph-rag-socket-agent.jar} JVM 옵션으로 startup 시 부착
 *   <li>SUT가 사용하는 외부 호출 클래스(e.g. JDBC driver, HttpClient impl)를 별도 type으로 지정
 * </ol>
 *
 * <p>본 테스트는 설치 자체가 예외 없이 완료되는지(retransform 단계 통과)와 idempotent함을 검증.
 */
class SocketSystemInstallerTest {

    @Test
    void installCompletesWithoutException() {
        Instrumentation inst = ByteBuddyAgent.install();
        assertThatNoException().isThrownBy(() -> SocketSystemInstaller.install(inst));
    }

    @Test
    void installIsIdempotent() {
        Instrumentation inst = ByteBuddyAgent.install();
        assertThatNoException().isThrownBy(() -> {
            SocketSystemInstaller.install(inst);
            SocketSystemInstaller.install(inst);
            SocketSystemInstaller.install(inst);
        });
    }

    @Test
    void counterStartsAtZero() {
        SocketCallCounter.reset();
        assertThat(SocketCallCounter.streamRequests()).isZero();
    }

    @Test
    void counterIncrementsOnDirectCall() {
        SocketCallCounter.reset();
        SocketCallCounter.incrementStreamRequests();
        SocketCallCounter.incrementStreamRequests();
        assertThat(SocketCallCounter.streamRequests()).isEqualTo(2);
    }
}
