package io.graphrag.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReflectiveBodyInstantiator 단위 테스트.
 * ExternalDto를 BeforeAll에서 동적 컴파일해 plain jar로 패키징한 뒤
 * 각 테스트에서 사용한다.
 */
class ReflectiveBodyInstantiatorTest {

    static Path plainJar;
    static String PLAIN_FQN = "io.graphrag.fixture.ExternalDto";
    static String JACKSON_FQN = "io.graphrag.fixture.CustomJacksonDto";

    @BeforeAll
    static void buildTestJars(@TempDir Path tmpDir) throws Exception {
        // --- ExternalDto (plain POJO) ---
        String externalDtoSrc = """
                package io.graphrag.fixture;
                public class ExternalDto {
                    private String name;
                    private int qty;
                    public String getName() { return name; }
                    public void setName(String n) { this.name = n; }
                    public int getQty() { return qty; }
                    public void setQty(int q) { this.qty = q; }
                }
                """;

        // --- CustomJacksonDto (has @JsonSerialize — dangerous) ---
        String customJacksonSrc = """
                package io.graphrag.fixture;
                import com.fasterxml.jackson.databind.annotation.JsonSerialize;
                @JsonSerialize
                public class CustomJacksonDto {
                    private String value;
                    public String getValue() { return value; }
                    public void setValue(String v) { this.value = v; }
                }
                """;

        plainJar = compileAndJar(tmpDir, "plain", List.of(
                new SourceFile("io/graphrag/fixture/ExternalDto.java", externalDtoSrc)
        ));

        // CustomJacksonDto: compile against jackson-annotations on classpath
        Path jacksonJar = findJacksonAnnotationsJar();
        Path customJar = compileAndJar(tmpDir, "custom-jackson", List.of(
                new SourceFile("io/graphrag/fixture/CustomJacksonDto.java", customJacksonSrc)
        ), jacksonJar);

        // Store in a field for the dangerous-annotation test
        JACKSON_JAR = customJar;
    }

    static Path JACKSON_JAR;
    static Path JACKSON_GETTER_JAR;
    static String JACKSON_GETTER_FQN = "io.graphrag.fixture.GetterJacksonDto";

    @BeforeAll
    static void buildGetterJacksonJar(@TempDir Path tmpDir2) throws Exception {
        // Dto with @JsonSerialize on a getter method (Fix 4 coverage)
        String src = """
                package io.graphrag.fixture;
                import com.fasterxml.jackson.databind.annotation.JsonSerialize;
                public class GetterJacksonDto {
                    private String value;
                    @JsonSerialize
                    public String getValue() { return value; }
                    public void setValue(String v) { this.value = v; }
                }
                """;
        Path jacksonJar = findJacksonAnnotationsJar();
        JACKSON_GETTER_JAR = compileAndJar(tmpDir2, "getter-jackson", List.of(
                new SourceFile("io/graphrag/fixture/GetterJacksonDto.java", src)
        ), jacksonJar);
    }

    @Test
    void resolvesPlainJarType() {
        var instantiator = new ReflectiveBodyInstantiator(true);
        Optional<ReflectiveBodyInstantiator.ReflectiveBody> result = instantiator.resolve(PLAIN_FQN, plainJar);

        assertThat(result).isPresent();
        ReflectiveBodyInstantiator.ReflectiveBody body = result.get();

        // shape should have 'name' and 'qty' fields
        List<String> fieldNames = body.shape().fields().stream()
                .map(BodyShape.BodyField::name)
                .toList();
        assertThat(fieldNames).containsExactlyInAnyOrder("name", "qty");

        // happyTemplate must be non-null and non-null node
        assertThat(body.happyTemplate()).isNotNull();
        assertThat(body.happyTemplate().has("name")).isTrue();
        assertThat(body.happyTemplate().has("qty")).isTrue();
    }

    @Test
    void deterministicSeed() {
        var instantiator = new ReflectiveBodyInstantiator(true);

        Optional<ReflectiveBodyInstantiator.ReflectiveBody> r1 = instantiator.resolve(PLAIN_FQN, plainJar);
        Optional<ReflectiveBodyInstantiator.ReflectiveBody> r2 = instantiator.resolve(PLAIN_FQN, plainJar);

        assertThat(r1).isPresent();
        assertThat(r2).isPresent();

        // Same seed → same JSON
        assertThat(r1.get().happyTemplate().toString())
                .isEqualTo(r2.get().happyTemplate().toString());
    }

    @Test
    void customJacksonFallsBackToEmpty() {
        var instantiator = new ReflectiveBodyInstantiator(true);
        Optional<ReflectiveBodyInstantiator.ReflectiveBody> result =
                instantiator.resolve(JACKSON_FQN, JACKSON_JAR);

        // Should return empty because class has @JsonSerialize annotation
        assertThat(result).isEmpty();
    }

    @Test
    void disabledReturnsEmpty() {
        var instantiator = new ReflectiveBodyInstantiator(false);
        Optional<ReflectiveBodyInstantiator.ReflectiveBody> result = instantiator.resolve(PLAIN_FQN, plainJar);
        assertThat(result).isEmpty();
    }

    @Test
    void customJacksonOnGetterFallsBackToEmpty() {
        // Fix 4: @JsonSerialize on a getter method should also be caught by the ASM visitor
        var instantiator = new ReflectiveBodyInstantiator(true);
        Optional<ReflectiveBodyInstantiator.ReflectiveBody> result =
                instantiator.resolve(JACKSON_GETTER_FQN, JACKSON_GETTER_JAR);
        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record SourceFile(String relativePath, String source) {}

    private static Path compileAndJar(Path tmpDir, String name, List<SourceFile> sources) throws Exception {
        return compileAndJar(tmpDir, name, sources, null);
    }

    private static Path compileAndJar(Path tmpDir, String name, List<SourceFile> sources,
                                       Path extraClasspath) throws Exception {
        Path srcDir = tmpDir.resolve(name + "-src");
        Path classDir = tmpDir.resolve(name + "-classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(classDir);

        // Write source files
        List<File> srcFiles = new java.util.ArrayList<>();
        for (SourceFile sf : sources) {
            Path srcPath = srcDir.resolve(sf.relativePath());
            Files.createDirectories(srcPath.getParent());
            Files.writeString(srcPath, sf.source(), StandardCharsets.UTF_8);
            srcFiles.add(srcPath.toFile());
        }

        // Compile
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available (need JDK, not JRE)");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {

            List<String> options = new java.util.ArrayList<>();
            options.add("-d");
            options.add(classDir.toString());
            if (extraClasspath != null) {
                options.add("-cp");
                options.add(extraClasspath.toString());
            }

            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(srcFiles);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                    options, null, compilationUnits);
            boolean success = task.call();
            if (!success) {
                StringBuilder sb = new StringBuilder("Compilation failed:\n");
                for (var d : diagnostics.getDiagnostics()) {
                    sb.append(d).append('\n');
                }
                throw new IllegalStateException(sb.toString());
            }
        }

        // Package into jar
        Path jarPath = tmpDir.resolve(name + ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jarPath.toFile()))) {
            packDir(classDir, classDir, zos);
        }
        return jarPath;
    }

    private static void packDir(Path root, Path dir, ZipOutputStream zos) throws IOException {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                packDir(root, f.toPath(), zos);
            } else {
                String entryName = root.relativize(f.toPath()).toString().replace(File.separatorChar, '/');
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(Files.readAllBytes(f.toPath()));
                zos.closeEntry();
            }
        }
    }

    /** Find jackson-annotations jar from the test classpath. */
    private static Path findJacksonAnnotationsJar() {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.contains("jackson-annotations") || entry.contains("jackson-databind")) {
                return Path.of(entry);
            }
        }
        // Fallback: just use current classpath (jackson is there as a transitive dep)
        // Return null means no extra cp — annotations on classpath already
        return null;
    }
}
