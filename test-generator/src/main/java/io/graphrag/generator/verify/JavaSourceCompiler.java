package io.graphrag.generator.verify;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 생성된 Java 소스를 메모리에서 javac로 컴파일하여 문법/타입 정합성 검증.
 *
 * <p>현재 프로세스의 classpath를 javac에 전달하므로, 생성 코드가 참조하는 라이브러리
 * (RestAssured, JUnit, JDBC 등)가 test-generator 테스트 classpath에 있어야 함.
 */
public final class JavaSourceCompiler {

    private JavaSourceCompiler() {}

    public static CompileResult compile(String fqClassName, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(false,
                    List.of("system java compiler unavailable (need JDK, not JRE)"));
        }

        DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
        Path outDir;
        try {
            outDir = Files.createTempDirectory("graph-rag-javac-");
        } catch (IOException ex) {
            return new CompileResult(false, List.of("failed to create temp dir: " + ex.getMessage()));
        }

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diag, null, null)) {
            JavaFileObject src = new InMemorySource(fqClassName, source);
            List<String> options = List.of(
                    "-d", outDir.toString(),
                    "-classpath", System.getProperty("java.class.path"));
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diag, options, null, List.of(src));
            boolean success = task.call();
            List<String> msgs = diag.getDiagnostics().stream()
                    .map(JavaSourceCompiler::format)
                    .collect(Collectors.toList());
            return new CompileResult(success, msgs);
        } catch (IOException ex) {
            return new CompileResult(false, List.of("compilation failed: " + ex.getMessage()));
        } finally {
            deleteRecursive(outDir);
        }
    }

    private static String format(Diagnostic<? extends JavaFileObject> d) {
        return d.getKind() + " at " + d.getLineNumber() + ":" + d.getColumnNumber() + " — " + d.getMessage(null);
    }

    private static void deleteRecursive(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { /* best effort */ }
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        private InMemorySource(String fqName, String code) {
            super(URI.create("string:///" + fqName.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
