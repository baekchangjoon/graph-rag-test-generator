package io.graphrag.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.Socket;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 5 실 {@link java.net.Socket} 자동 instrumentation.
 *
 * <p>{@link AgentMain}이 기본 sample 타겟만 다루는 반면, 본 installer는 system class
 * ({@link java.net.Socket})를 retransform한다.
 *
 * <p>주의: system class instrumentation은 다음 제약을 요구한다:
 * <ul>
 *   <li>Boot-Class-Path 또는 {@code Instrumentation.appendToBootstrapClassLoaderSearch}로
 *       Advice 클래스가 bootstrap classloader에서 접근 가능해야 함
 *   <li>{@code RedefinitionStrategy.RETRANSFORMATION} 사용
 *   <li>실시간 retransform이라 class format 변경은 제한적
 * </ul>
 *
 * <p>본 구현은 단순 카운터 advice — Socket의 {@code getInputStream}/{@code getOutputStream}
 * 호출 횟수를 {@link SocketCallCounter}에 누적. 실 stream 교체는 {@link SocketByteRecorder}로
 * 명시적 wrap이 권장 (system instrumentation은 부작용 가능성이 큼).
 */
public final class SocketSystemInstaller {

    private static volatile boolean installed = false;

    private SocketSystemInstaller() {}

    public static synchronized void install(Instrumentation inst) {
        if (installed) return;
        installed = true;
        try {
            injectIntoBootstrap(inst, SocketCallCounter.class, SocketStreamAdvice.class);
        } catch (Exception e) {
            throw new IllegalStateException("bootstrap injection failed", e);
        }
        new AgentBuilder.Default()
                .ignore(ElementMatchers.none())
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .type(ElementMatchers.is(Socket.class))
                .transform((builder, type, loader, module, pd) ->
                        builder.visit(Advice.to(SocketStreamAdvice.class)
                                .on(ElementMatchers.namedOneOf("getInputStream", "getOutputStream"))))
                .installOn(inst);
    }

    /** {@link Socket} 같은 system class에서 참조 가능하도록 helper 클래스를 bootstrap classloader에 주입. */
    private static void injectIntoBootstrap(Instrumentation inst, Class<?>... classes) throws Exception {
        File temp = Files.createTempDirectory("graphrag-bootstrap-").toFile();
        temp.deleteOnExit();
        Map<TypeDescription, byte[]> types = new LinkedHashMap<>();
        for (Class<?> c : classes) {
            types.put(new TypeDescription.ForLoadedType(c),
                    ClassFileLocator.ForClassLoader.read(c));
        }
        ClassInjector.UsingInstrumentation
                .of(temp, ClassInjector.UsingInstrumentation.Target.BOOTSTRAP, inst)
                .injectRaw(toBinaryNameMap(types));
    }

    private static Map<String, byte[]> toBinaryNameMap(Map<TypeDescription, byte[]> in) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<TypeDescription, byte[]> e : in.entrySet()) {
            out.put(e.getKey().getName(), e.getValue());
        }
        return out;
    }

    /** Advice. {@link SocketCallCounter}에 호출 횟수 누적. */
    public static class SocketStreamAdvice {
        @Advice.OnMethodEnter
        public static void onEnter() {
            SocketCallCounter.incrementStreamRequests();
        }
    }
}
