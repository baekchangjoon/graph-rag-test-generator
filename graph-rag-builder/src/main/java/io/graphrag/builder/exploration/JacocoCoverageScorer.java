package io.graphrag.builder.exploration;

import io.graphrag.model.SampleInput;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * JaCoCo Core API를 in-process로 부착해 임의의 클래스에 대해
 * line/branch 커버리지를 측정하고 {@link CoverageSignature}를 산출한다.
 *
 * <p>외부 {@code -javaagent:jacocoagent.jar} 없이 작동 — JaCoCo의
 * {@link LoggerRuntime} + {@link Instrumenter} + 메모리 ClassLoader 패턴 사용.
 *
 * <p>사용:
 * <pre>
 * JacocoCoverageScorer scorer = JacocoCoverageScorer.forClass(MyTarget.class);
 * try {
 *     Function&lt;SampleInput, CoverageSignature&gt; f = input -&gt; scorer.score(loaded -&gt; {
 *         MyTarget t = (MyTarget) loaded.getDeclaredConstructor().newInstance();
 *         t.handle(input);
 *     });
 * } finally {
 *     scorer.close();
 * }
 * </pre>
 *
 * <p>{@link CoverageGuidedFuzzer}와 결합 시 fuzzer가 실 line/branch 누적 변화를 토대로
 * input 다양성 평가.
 */
public final class JacocoCoverageScorer implements AutoCloseable {

    private final String targetClassName;
    private final byte[] originalBytes;
    private final byte[] instrumentedBytes;
    private final IRuntime runtime;
    private final RuntimeData runtimeData;

    private JacocoCoverageScorer(String targetClassName,
                                 byte[] originalBytes,
                                 byte[] instrumentedBytes,
                                 IRuntime runtime,
                                 RuntimeData runtimeData) {
        this.targetClassName = targetClassName;
        this.originalBytes = originalBytes;
        this.instrumentedBytes = instrumentedBytes;
        this.runtime = runtime;
        this.runtimeData = runtimeData;
    }

    public static JacocoCoverageScorer forClass(Class<?> target) {
        try {
            String name = target.getName();
            String resource = name.replace('.', '/') + ".class";
            byte[] original;
            try (InputStream is = target.getClassLoader().getResourceAsStream(resource)) {
                if (is == null) {
                    throw new IllegalStateException("class bytes not found: " + resource);
                }
                original = is.readAllBytes();
            }
            IRuntime rt = new LoggerRuntime();
            RuntimeData data = new RuntimeData();
            rt.startup(data);

            Instrumenter instrumenter = new Instrumenter(rt);
            byte[] instrumented = instrumenter.instrument(original, name);
            return new JacocoCoverageScorer(name, original, instrumented, rt, data);
        } catch (Exception ex) {
            throw new RuntimeException("JaCoCo scorer init failed", ex);
        }
    }

    /**
     * 사용자 {@code invocation}을 instrumented 버전의 target class로 호출하고,
     * 그동안 측정된 라인/브랜치 hit을 {@link CoverageSignature}로 변환.
     *
     * @param invocation instrumented {@code Class}를 받아 임의 인스턴스/메소드 실행
     */
    public CoverageSignature score(Invocation invocation) {
        MemoryClassLoader loader = new MemoryClassLoader(
                Thread.currentThread().getContextClassLoader());
        loader.addClass(targetClassName, instrumentedBytes);
        try {
            Class<?> instrumented = loader.loadClass(targetClassName);
            invocation.invoke(instrumented);
        } catch (Throwable t) {
            // 실행 자체가 실패해도 부분 커버리지는 의미가 있음. signature는 비어 있을 수 있음.
        }

        ExecutionDataStore execData = new ExecutionDataStore();
        SessionInfoStore sessions = new SessionInfoStore();
        runtimeData.collect(execData, sessions, true);

        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(execData, coverageBuilder);
        try {
            analyzer.analyzeClass(new ByteArrayInputStream(originalBytes), targetClassName);
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
        return toSignature(coverageBuilder);
    }

    private CoverageSignature toSignature(CoverageBuilder cb) {
        TreeSet<Integer> hitLines = new TreeSet<>();
        TreeMap<String, Boolean> hitBranches = new TreeMap<>();
        for (IClassCoverage cc : cb.getClasses()) {
            for (IMethodCoverage mc : cc.getMethods()) {
                int first = mc.getFirstLine();
                int last = mc.getLastLine();
                if (first < 0 || last < 0) continue;
                for (int line = first; line <= last; line++) {
                    if (mc.getLine(line).getInstructionCounter().getCoveredCount() > 0) {
                        hitLines.add(line);
                    }
                    if (mc.getLine(line).getBranchCounter().getTotalCount() > 0) {
                        boolean fullyCovered =
                                mc.getLine(line).getBranchCounter().getMissedCount() == 0;
                        hitBranches.put(cc.getName() + "#" + mc.getName() + "@" + line,
                                fullyCovered);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("L:");
        for (Integer line : hitLines) sb.append(line).append(',');
        sb.append("|B:");
        for (Map.Entry<String, Boolean> e : hitBranches.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(',');
        }
        return new CoverageSignature(sha256Hex(sb.toString()));
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(s.hashCode());
        }
    }

    @Override
    public void close() {
        runtime.shutdown();
    }

    /** Functional Invocation supporting checked exceptions. */
    @FunctionalInterface
    public interface Invocation {
        void invoke(Class<?> instrumentedClass) throws Exception;
    }

    /** 단일 클래스 byte를 메모리에 들고 있다가 로드. */
    static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions = new HashMap<>();

        MemoryClassLoader(ClassLoader parent) { super(parent); }

        void addClass(String name, byte[] bytes) { definitions.put(name, bytes); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes != null) {
                Class<?> c = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }

    /** Convenience: SampleInput-aware scorer factory. */
    public Function<SampleInput, CoverageSignature> asScorer(SampleInputInvocation invocation) {
        return input -> score(cls -> invocation.invoke(cls, input));
    }

    @FunctionalInterface
    public interface SampleInputInvocation {
        void invoke(Class<?> instrumentedClass, SampleInput input) throws Exception;
    }
}
