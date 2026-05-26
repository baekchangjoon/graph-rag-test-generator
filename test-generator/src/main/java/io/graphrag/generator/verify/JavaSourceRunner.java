package io.graphrag.generator.verify;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 생성된 Java 소스를 컴파일 + 실행해서 JUnit 결과까지 검증.
 *
 * <p>compile 단계는 {@link JavaSourceCompiler}와 동일. 추가로 컴파일된 클래스를 동적 로드하여
 * JUnit Platform Launcher로 실행, 성공/실패 카운트를 보고.
 *
 * <p>주의: 실행되는 테스트가 환경변수 (APP_BASE_URI 등)에 의존하면 실패할 수 있음. 진짜 self-check
 * 용도는 환경 무관한 trivial test 또는 단위 테스트에 적용.
 */
public final class JavaSourceRunner {

    private JavaSourceRunner() {}

    public static RunResult compileAndRun(String fqClassName, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new RunResult(false, 0, 0,
                    List.of("system java compiler unavailable (need JDK)"));
        }

        Path outDir;
        try {
            outDir = Files.createTempDirectory("graph-rag-runner-");
        } catch (IOException ex) {
            return new RunResult(false, 0, 0,
                    List.of("temp dir creation failed: " + ex.getMessage()));
        }

        DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
        boolean compiled;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diag, null, null)) {
            JavaFileObject src = new InMemorySource(fqClassName, source);
            List<String> options = List.of(
                    "-d", outDir.toString(),
                    "-classpath", System.getProperty("java.class.path"));
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diag, options, null, List.of(src));
            compiled = task.call();
        } catch (IOException ex) {
            deleteRecursive(outDir);
            return new RunResult(false, 0, 0,
                    List.of("compilation failed: " + ex.getMessage()));
        }

        List<String> diagnostics = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics()) {
            diagnostics.add(d.getKind() + " " + d.getLineNumber() + ":" + d.getColumnNumber()
                    + " — " + d.getMessage(null));
        }

        if (!compiled) {
            deleteRecursive(outDir);
            return new RunResult(false, 0, 0, diagnostics);
        }

        try {
            URL outUrl = outDir.toUri().toURL();
            try (URLClassLoader loader = new URLClassLoader(
                    new URL[] {outUrl}, JavaSourceRunner.class.getClassLoader())) {

                Class<?> testClass = Class.forName(fqClassName, true, loader);

                LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectClass(testClass))
                        .build();

                SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
                Launcher launcher = LauncherFactory.create();
                launcher.registerTestExecutionListeners(summaryListener);
                launcher.execute(request);

                TestExecutionSummary summary = summaryListener.getSummary();
                long total = summary.getTestsFoundCount();
                long failed = summary.getTestsFailedCount();
                List<String> failureMsgs = summary.getFailures().stream()
                        .map(f -> f.getTestIdentifier().getDisplayName() + ": " + f.getException().getMessage())
                        .collect(Collectors.toList());

                List<String> allDiagnostics = new ArrayList<>(diagnostics);
                allDiagnostics.addAll(failureMsgs);

                return new RunResult(true, total, failed, allDiagnostics);
            }
        } catch (Exception ex) {
            diagnostics.add("runner failure: " + ex.getMessage());
            return new RunResult(true, 0, 0, diagnostics);
        } finally {
            deleteRecursive(outDir);
        }
    }

    private static void deleteRecursive(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {}
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;
        InMemorySource(String fqName, String code) {
            super(URI.create("string:///" + fqName.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
    }
}
