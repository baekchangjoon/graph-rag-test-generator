# graph-rag-builder Static Analysis T1+T2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JavaParser-based static analyzer to `graph-rag-builder` that parses Spring Boot source trees and produces `List<Endpoint>` plus structured branch/call-graph metadata, per spec at `docs/superpowers/specs/2026-05-28-graph-rag-builder-static-analysis-design.md`.

**Architecture:** Two new packages inside the existing `graph-rag-builder` module: `io.graphrag.builder.staticanalysis.ast` (file walk → parsed `CompilationUnit` collection + `SymbolSolver`) and `io.graphrag.builder.staticanalysis.domain` (class-role classifier, branch extractor, call-graph builder, endpoint extractor, orchestrator). All output is in-memory only this session — no JSON file writing, no CLI, no orchestrator switch (deferred to T3+CLI follow-up session).

**Tech Stack:** Java 17, JavaParser symbol-solver 3.26.2, JUnit 5.10.2, AssertJ 3.26.3, Gradle 8.13 (`-Pagent.enabled=true` build flag is required because of an unrelated pre-existing compile dependency in `graph-rag-builder/capture/ArchiveShutdownWriter.java`).

**Working directory:** `/Users/changjoonbaek/graph-rag/graph-rag` (branch `feat/t6-orchestrator`).

**Build command shorthand:** `./gw` below means
```
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true
```
Set this once per shell:
```bash
export JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home
alias gw='./gradlew -Pagent.enabled=true'
```

---

## File Structure (locked in before tasks)

```
graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/
├── ast/
│   ├── package-info.java
│   ├── AstParseResult.java          (record)
│   ├── ParsedFile.java              (record)
│   ├── ParseFailure.java            (record)
│   ├── SymbolResolverFactory.java
│   └── AstParser.java
└── domain/
    ├── package-info.java
    ├── ClassRole.java               (enum)
    ├── BranchKind.java              (enum)
    ├── Parameter.java               (record)
    ├── ReturnType.java              (record)
    ├── Branch.java                  (record)
    ├── MethodCall.java              (record)
    ├── MethodAnalysis.java          (record)
    ├── CallGraph.java               (record)
    ├── DomainAnalysisResult.java    (record)
    ├── ClassRoleClassifier.java
    ├── BranchExtractor.java
    ├── CallGraphBuilder.java
    ├── EndpointExtractor.java
    └── DomainAnalyzer.java

graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/
├── ast/
│   ├── AstParserTest.java
│   └── SymbolResolverFactoryTest.java
└── domain/
    ├── ClassRoleClassifierTest.java
    ├── BranchExtractorTest.java
    ├── CallGraphBuilderTest.java
    ├── EndpointExtractorTest.java
    ├── DomainAnalyzerTest.java
    └── DomainAnalyzerPetclinicTest.java

graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/
└── org/example/petclinic/
    ├── Owner.java
    ├── OwnerRepository.java
    ├── OwnerService.java
    └── OwnerRestController.java
```

Each file has one clear responsibility. Records are split per concept so they remain small and self-documenting.

---

## Task 1: Add JavaParser dependency + create package skeletons

**Files:**
- Modify: `graph-rag-builder/build.gradle.kts`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/package-info.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/package-info.java`

- [ ] **Step 1: Read current dependencies block**

Confirm the current contents of `graph-rag-builder/build.gradle.kts` around the `dependencies {` block. We add the JavaParser line at the end of that block, after the existing `compileOnly("io.opentelemetry:opentelemetry-api:1.49.0")` line.

- [ ] **Step 2: Add JavaParser dependency**

Edit `graph-rag-builder/build.gradle.kts`. After this line:
```kotlin
    compileOnly("io.opentelemetry:opentelemetry-api:1.49.0")
```
Insert before the next blank line / `testImplementation` block:
```kotlin

    // JavaParser symbol-solver — Stage 1 static analysis (staticanalysis package).
    // Version aligned with :path-discovery-static (javaparser-core 3.26.2).
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.26.2")
```

- [ ] **Step 3: Create ast package marker**

Create `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/package-info.java`:

```java
/**
 * Stage 1 of the static analyzer: JavaParser-driven AST extraction.
 *
 * <p>{@link io.graphrag.builder.staticanalysis.ast.AstParser} walks a Spring
 * source tree, parses each {@code .java} file deterministically, and returns
 * {@link io.graphrag.builder.staticanalysis.ast.AstParseResult} containing the
 * successfully-parsed {@link com.github.javaparser.ast.CompilationUnit}s plus
 * any per-file failures (un-parseable files are isolated, not fatal).
 *
 * <p>Downstream package {@code io.graphrag.builder.staticanalysis.domain}
 * consumes the AST collection to classify class roles, extract Spring
 * endpoints, and compute branches + call graph.
 */
package io.graphrag.builder.staticanalysis.ast;
```

- [ ] **Step 4: Create domain package marker**

Create `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/package-info.java`:

```java
/**
 * Stage 2 of the static analyzer: domain-model classification + endpoint
 * extraction.
 *
 * <p>{@link io.graphrag.builder.staticanalysis.domain.DomainAnalyzer} takes
 * the {@link io.graphrag.builder.staticanalysis.ast.AstParseResult} from
 * Stage 1 and produces {@link io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult}:
 * a deterministic list of {@code shared-model} {@code Endpoint}s plus the
 * {@code MethodAnalysis} / {@code CallGraph} structures that Stage 3 (branch
 * → sample input generation, future session) will consume.
 */
package io.graphrag.builder.staticanalysis.domain;
```

- [ ] **Step 5: Verify graph-rag-builder still compiles**

Run:
```bash
./gw :graph-rag-builder:compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/build.gradle.kts \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/package-info.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/package-info.java
git commit -m "$(cat <<'EOF'
chore(staticanalysis): scaffold ast/ + domain/ packages with JavaParser dep

Adds com.github.javaparser:javaparser-symbol-solver-core:3.26.2 (version
aligned with :path-discovery-static) and empty package markers for the
upcoming T1+T2 implementation. No production code yet.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: AST records (`ParsedFile`, `ParseFailure`, `AstParseResult`)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/ParsedFile.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/ParseFailure.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/AstParseResult.java`

These are plain data carriers — no tests required at this step. Their behavior is exercised by `AstParserTest` in Task 4.

- [ ] **Step 1: Create `ParsedFile.java`**

```java
package io.graphrag.builder.staticanalysis.ast;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A single successfully-parsed Java source file.
 *
 * @param sourcePath  absolute path to the {@code .java} file
 * @param packageName the file's declared package, or {@code ""} for the default package
 * @param className   top-level class/interface/enum/record simple name, or {@code ""}
 *                    for files that only contain a {@code package-info}
 * @param cu          JavaParser AST root, never {@code null}
 */
public record ParsedFile(Path sourcePath, String packageName, String className, CompilationUnit cu) {

    public ParsedFile {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(cu, "cu");
    }
}
```

- [ ] **Step 2: Create `ParseFailure.java`**

```java
package io.graphrag.builder.staticanalysis.ast;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One {@code .java} file that {@link AstParser} could not parse. The rest of
 * the parse keeps going — a single broken file never aborts the whole scan.
 */
public record ParseFailure(Path sourcePath, String message) {

    public ParseFailure {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(message, "message");
    }
}
```

- [ ] **Step 3: Create `AstParseResult.java`**

```java
package io.graphrag.builder.staticanalysis.ast;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of running {@link AstParser#parse(java.nio.file.Path)} on a source
 * directory. Both lists are returned in deterministic order (path sort order),
 * so two runs on the same input produce equal results.
 */
public record AstParseResult(List<ParsedFile> parsedFiles, List<ParseFailure> failures) {

    public AstParseResult {
        parsedFiles = List.copyOf(Objects.requireNonNull(parsedFiles, "parsedFiles"));
        failures    = List.copyOf(Objects.requireNonNull(failures,    "failures"));
    }
}
```

- [ ] **Step 4: Verify compile**

Run:
```bash
./gw :graph-rag-builder:compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/ParsedFile.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/ParseFailure.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/AstParseResult.java
git commit -m "feat(staticanalysis): ast records (ParsedFile, ParseFailure, AstParseResult)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `SymbolResolverFactory`

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/SymbolResolverFactory.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/ast/SymbolResolverFactoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/ast/SymbolResolverFactoryTest.java`:

```java
package io.graphrag.builder.staticanalysis.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolResolverFactoryTest {

    @Test
    void resolves_in_source_method_call(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("Demo.java");
        Files.writeString(src, """
            package demo;
            class Demo {
                int add(int a, int b) { return a + b; }
                int call() { return add(1, 2); }
            }
            """);

        JavaSymbolSolver solver = SymbolResolverFactory.create(tmp, List.of());
        JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));

        CompilationUnit cu = parser.parse(src).getResult().orElseThrow();
        MethodCallExpr call = cu.findFirst(MethodCallExpr.class).orElseThrow();

        assertThat(call.resolve().getName()).isEqualTo("add");
        assertThat(call.resolve().declaringType().getQualifiedName()).isEqualTo("demo.Demo");
    }

    @Test
    void unresolvable_call_does_not_throw_at_solver_creation(@TempDir Path tmp) {
        // Solver construction itself never blows up, even when no jars / no sources are provided.
        JavaSymbolSolver solver = SymbolResolverFactory.create(tmp, List.of());
        assertThat(solver).isNotNull();
    }

    @Test
    void resolving_unknown_call_throws_UnsolvedSymbolException(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("Demo.java");
        Files.writeString(src, """
            package demo;
            class Demo { void call() { com.example.External.unknown(); } }
            """);

        JavaSymbolSolver solver = SymbolResolverFactory.create(tmp, List.of());
        JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));

        CompilationUnit cu = parser.parse(src).getResult().orElseThrow();
        MethodCallExpr call = cu.findFirst(MethodCallExpr.class).orElseThrow();

        // The resolve() call itself throws — callers are responsible for catching this.
        assertThatThrownBy(call::resolve).isInstanceOf(UnsolvedSymbolException.class);
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable>
            assertThatThrownBy(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(c);
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compilation error)**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.ast.SymbolResolverFactoryTest"
```
Expected: BUILD FAILED with `error: cannot find symbol  symbol: class SymbolResolverFactory`.

- [ ] **Step 3: Implement `SymbolResolverFactory`**

Create `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/SymbolResolverFactory.java`:

```java
package io.graphrag.builder.staticanalysis.ast;

import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Builds a {@link JavaSymbolSolver} backed by:
 * <ul>
 *   <li>{@link ReflectionTypeSolver} — JDK + classpath types reachable from this JVM.</li>
 *   <li>{@link JavaParserTypeSolver} on the SUT source root — in-project type resolution.</li>
 *   <li>{@link JarTypeSolver} per element of {@code classpathJars} — optional, e.g. for
 *       resolving Spring/Jakarta annotation types when we want full qualified-name checks.</li>
 * </ul>
 *
 * <p>Any single solver can fail to resolve; that is normal. Callers catch
 * {@code UnsolvedSymbolException} from {@code Resolvable#resolve()} call sites.
 */
public final class SymbolResolverFactory {

    private SymbolResolverFactory() {}

    public static JavaSymbolSolver create(Path sourceRoot, List<Path> classpathJars) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(classpathJars, "classpathJars");

        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(new ReflectionTypeSolver(false));
        if (Files.isDirectory(sourceRoot)) {
            combined.add(new JavaParserTypeSolver(sourceRoot));
        }
        for (Path jar : classpathJars) {
            try {
                combined.add(new JarTypeSolver(jar));
            } catch (IOException ex) {
                // A bad jar should never silently break resolution — but at construction time
                // we degrade gracefully by skipping that jar. Caller logs are at the AstParser layer.
                throw new IllegalArgumentException("cannot read jar " + jar + ": " + ex.getMessage(), ex);
            }
        }
        return new JavaSymbolSolver(combined);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.ast.SymbolResolverFactoryTest"
```
Expected: 3 tests, all PASS, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/SymbolResolverFactory.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/ast/SymbolResolverFactoryTest.java
git commit -m "feat(staticanalysis): SymbolResolverFactory with reflection+source+jar solvers

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `AstParser` — happy paths (empty dir + single file + package-info)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/AstParser.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/ast/AstParserTest.java`

- [ ] **Step 1: Write the failing tests (happy-path subset)**

Create `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/ast/AstParserTest.java`:

```java
package io.graphrag.builder.staticanalysis.ast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AstParserTest {

    @Test
    void empty_dir_yields_empty_result(@TempDir Path tmp) throws Exception {
        AstParseResult r = AstParser.parse(tmp);
        assertThat(r.parsedFiles()).isEmpty();
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void parses_single_valid_file(@TempDir Path tmp) throws Exception {
        Path pkgDir = tmp.resolve("a/b");
        Files.createDirectories(pkgDir);
        Path src = pkgDir.resolve("Hello.java");
        Files.writeString(src, """
            package a.b;
            public class Hello { String greet() { return "hi"; } }
            """);

        AstParseResult r = AstParser.parse(tmp);

        assertThat(r.failures()).isEmpty();
        assertThat(r.parsedFiles()).hasSize(1);
        ParsedFile p = r.parsedFiles().get(0);
        assertThat(p.sourcePath()).isEqualTo(src);
        assertThat(p.packageName()).isEqualTo("a.b");
        assertThat(p.className()).isEqualTo("Hello");
        assertThat(p.cu()).isNotNull();
    }

    @Test
    void package_info_files_are_tolerated(@TempDir Path tmp) throws Exception {
        Path pkgDir = tmp.resolve("a/b");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("package-info.java"), "package a.b;\n");

        AstParseResult r = AstParser.parse(tmp);

        assertThat(r.failures()).isEmpty();
        assertThat(r.parsedFiles()).hasSize(1);
        // package-info has no class — className is "".
        assertThat(r.parsedFiles().get(0).packageName()).isEqualTo("a.b");
        assertThat(r.parsedFiles().get(0).className()).isEmpty();
    }

    @Test
    void result_is_sorted_by_path(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a/A.java");
        Path b = tmp.resolve("b/B.java");
        Path c = tmp.resolve("c/C.java");
        Files.createDirectories(a.getParent());
        Files.createDirectories(b.getParent());
        Files.createDirectories(c.getParent());
        Files.writeString(c, "package c; class C {}\n");
        Files.writeString(a, "package a; class A {}\n");
        Files.writeString(b, "package b; class B {}\n");

        AstParseResult r = AstParser.parse(tmp);

        assertThat(r.parsedFiles()).extracting(ParsedFile::className)
                .containsExactly("A", "B", "C");
    }

    @Test
    void parse_is_deterministic(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("p"));
        Files.writeString(tmp.resolve("p/X.java"), "package p; class X {}\n");
        Files.writeString(tmp.resolve("p/Y.java"), "package p; class Y {}\n");

        AstParseResult r1 = AstParser.parse(tmp);
        AstParseResult r2 = AstParser.parse(tmp);

        assertThat(r1.parsedFiles()).extracting(ParsedFile::className)
                .isEqualTo(r2.parsedFiles().stream().map(ParsedFile::className).toList());
    }

    @Test
    void broken_file_isolated_in_failures(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("p"));
        Files.writeString(tmp.resolve("p/Good.java"), "package p; class Good {}\n");
        Files.writeString(tmp.resolve("p/Bad.java"), "package p; class { this is not java }\n");

        AstParseResult r = AstParser.parse(tmp);

        assertThat(r.parsedFiles()).extracting(ParsedFile::className).containsExactly("Good");
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).sourcePath().getFileName().toString()).isEqualTo("Bad.java");
        assertThat(r.failures().get(0).message()).isNotBlank();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (compile error)**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.ast.AstParserTest"
```
Expected: BUILD FAILED with `cannot find symbol  symbol: class AstParser`.

- [ ] **Step 3: Implement `AstParser`**

Create `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/AstParser.java`:

```java
package io.graphrag.builder.staticanalysis.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Walks a Spring source directory, parses every {@code .java} file with
 * JavaParser, and returns an {@link AstParseResult} containing the parsed
 * compilation units in deterministic (path-sorted) order plus a separate
 * list of files that failed to parse.
 *
 * <p>Per-file failures are isolated: a broken {@code .java} file is
 * captured in {@code failures} but the remaining files are still parsed.
 * Filesystem errors (e.g. unreadable source root) propagate as {@code IOException}.
 *
 * @see SymbolResolverFactory
 */
public final class AstParser {

    private AstParser() {}

    /** Parse {@code sourceRoot} with no external classpath jars. */
    public static AstParseResult parse(Path sourceRoot) throws IOException {
        return parse(sourceRoot, List.of());
    }

    /**
     * Parse {@code sourceRoot}, configuring the symbol solver with the given jars.
     *
     * @param sourceRoot    directory to walk for {@code .java} files (recursive)
     * @param classpathJars additional jars to feed the {@link SymbolResolverFactory}
     */
    public static AstParseResult parse(Path sourceRoot, List<Path> classpathJars) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(classpathJars, "classpathJars");

        if (!Files.isDirectory(sourceRoot)) {
            return new AstParseResult(List.of(), List.of());
        }

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        ParserConfiguration cfg = new ParserConfiguration()
                .setSymbolResolver(SymbolResolverFactory.create(sourceRoot, classpathJars));
        JavaParser parser = new JavaParser(cfg);

        List<ParsedFile> parsed = new ArrayList<>(javaFiles.size());
        List<ParseFailure> failures = new ArrayList<>();
        for (Path src : javaFiles) {
            parseOne(parser, src, parsed, failures);
        }
        return new AstParseResult(parsed, failures);
    }

    private static void parseOne(JavaParser parser, Path src,
                                 List<ParsedFile> parsed, List<ParseFailure> failures) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(src);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                failures.add(new ParseFailure(src, result.getProblems().isEmpty()
                        ? "unknown parse error"
                        : result.getProblems().get(0).getMessage()));
                return;
            }
            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            String className = cu.getTypes().stream()
                    .findFirst()
                    .map(TypeDeclaration::getNameAsString)
                    .orElse("");
            parsed.add(new ParsedFile(src, pkg, className, cu));
        } catch (Throwable t) {
            failures.add(new ParseFailure(src, t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }
}
```

- [ ] **Step 4: Run tests to verify all pass**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.ast.AstParserTest"
```
Expected: 6 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/AstParser.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/ast/AstParserTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): AstParser — deterministic file walk + per-file failure isolation

Recursively parses every .java file under sourceRoot, sorted by path string,
with a SymbolSolver-aware JavaParser. Broken files captured in failures list;
other files keep parsing. package-info files tolerated (className=\"\").

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Domain records (`ClassRole`, `BranchKind`, `Parameter`, `ReturnType`, `Branch`, `MethodCall`, `MethodAnalysis`, `CallGraph`, `DomainAnalysisResult`)

These are pure data carriers. We create them in one task so later component tasks have all type-level vocabulary available.

**Files:** all under `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/`:
- `ClassRole.java`, `BranchKind.java`, `Parameter.java`, `ReturnType.java`, `Branch.java`, `MethodCall.java`, `MethodAnalysis.java`, `CallGraph.java`, `DomainAnalysisResult.java`

- [ ] **Step 1: Create `ClassRole.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

/**
 * High-level role of a Java class in a Spring application. Set by
 * {@link ClassRoleClassifier} from annotation simple names plus
 * {@code extends}/{@code implements} of well-known Spring Data interfaces.
 */
public enum ClassRole {
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    DOMAIN,
    OTHER
}
```

- [ ] **Step 2: Create `BranchKind.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

/** AST node category that {@link BranchExtractor} surfaces as a {@link Branch}. */
public enum BranchKind {
    IF,
    SWITCH,
    TERNARY,
    THROW,
    RETURN
}
```

- [ ] **Step 3: Create `Parameter.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Objects;

/**
 * A method parameter as seen at the AST level. Annotation simple names are
 * preserved verbatim (e.g. {@code "PathVariable"}, {@code "RequestBody"}).
 */
public record Parameter(String name, String type, List<String> annotations) {

    public Parameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
    }
}
```

- [ ] **Step 4: Create `ReturnType.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

import java.util.Objects;

/** Method return type. {@code isVoid} is convenience derived from {@code type.equals("void")}. */
public record ReturnType(String type, boolean isVoid) {

    public ReturnType {
        Objects.requireNonNull(type, "type");
    }

    public static ReturnType of(String type) {
        return new ReturnType(type, "void".equals(type));
    }
}
```

- [ ] **Step 5: Create `Branch.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Objects;

/**
 * One control-flow branch surfaced from a method body.
 *
 * @param id                  stable identifier: {@code "{classFqn}#{method}:line{N}"}
 * @param kind                AST node category (see {@link BranchKind})
 * @param condition           raw source text of the condition (may be empty for THROW/RETURN)
 * @param lineNumber          1-based source line of the AST node
 * @param referencedVariables identifiers appearing in {@code condition}, deduplicated and sorted
 */
public record Branch(
        String id,
        BranchKind kind,
        String condition,
        int lineNumber,
        List<String> referencedVariables) {

    public Branch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(condition, "condition");
        referencedVariables = List.copyOf(Objects.requireNonNull(referencedVariables, "referencedVariables"));
    }
}
```

- [ ] **Step 6: Create `MethodCall.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

import java.util.Objects;

/**
 * One outgoing method call from a method body. When the symbol solver cannot
 * resolve the target, {@code calleeClassFqn} is {@code null} and {@code resolved}
 * is {@code false}.
 */
public record MethodCall(
        String calleeClassFqn,         // nullable
        String calleeMethodName,
        int line,
        boolean resolved) {

    public MethodCall {
        Objects.requireNonNull(calleeMethodName, "calleeMethodName");
    }
}
```

- [ ] **Step 7: Create `MethodAnalysis.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Objects;

/**
 * Per-method analysis bundle, keyed by {@code "{classFqn}#{methodName}"} in
 * {@link DomainAnalysisResult#methodAnalyses()}.
 */
public record MethodAnalysis(
        String classFqn,
        String methodName,
        List<Parameter> parameters,
        List<Branch> branches,
        List<MethodCall> outgoingCalls,
        ReturnType returnType) {

    public MethodAnalysis {
        Objects.requireNonNull(classFqn, "classFqn");
        Objects.requireNonNull(methodName, "methodName");
        parameters    = List.copyOf(Objects.requireNonNull(parameters,    "parameters"));
        branches      = List.copyOf(Objects.requireNonNull(branches,      "branches"));
        outgoingCalls = List.copyOf(Objects.requireNonNull(outgoingCalls, "outgoingCalls"));
        Objects.requireNonNull(returnType, "returnType");
    }

    /** Convenience: key used in {@link DomainAnalysisResult#methodAnalyses()}. */
    public String key() {
        return classFqn + "#" + methodName;
    }
}
```

- [ ] **Step 8: Create `CallGraph.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-project call edges only. {@code edges()} key is {@code "{classFqn}#{methodName}"};
 * value is the deterministic list of callee keys reachable in one hop.
 * External-library calls are excluded.
 */
public record CallGraph(Map<String, List<String>> edges) {

    public CallGraph {
        Objects.requireNonNull(edges, "edges");
        // Defensive deep copy to keep the record truly immutable.
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        edges.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        edges = java.util.Collections.unmodifiableMap(copy);
    }

    public static CallGraph empty() {
        return new CallGraph(Map.of());
    }
}
```

- [ ] **Step 9: Create `DomainAnalysisResult.java`**

```java
package io.graphrag.builder.staticanalysis.domain;

import io.graphrag.model.Endpoint;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of {@link DomainAnalyzer#analyze}.
 *
 * <p>All collections are deterministic:
 * <ul>
 *   <li>{@code endpoints} sorted by {@code (path, method)}.</li>
 *   <li>{@code classRoles}, {@code methodAnalyses} are insertion-ordered
 *       (insertion = path-sorted ParsedFile order).</li>
 * </ul>
 */
public record DomainAnalysisResult(
        List<Endpoint> endpoints,
        Map<String, ClassRole> classRoles,
        Map<String, MethodAnalysis> methodAnalyses,
        CallGraph callGraph) {

    public DomainAnalysisResult {
        endpoints       = List.copyOf(Objects.requireNonNull(endpoints,       "endpoints"));
        classRoles      = Map.copyOf(Objects.requireNonNull(classRoles,      "classRoles"));
        methodAnalyses  = Map.copyOf(Objects.requireNonNull(methodAnalyses,  "methodAnalyses"));
        Objects.requireNonNull(callGraph, "callGraph");
    }
}
```

- [ ] **Step 10: Verify compile**

Run:
```bash
./gw :graph-rag-builder:compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/ClassRole.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/BranchKind.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/Parameter.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/ReturnType.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/Branch.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/MethodCall.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/MethodAnalysis.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/CallGraph.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/DomainAnalysisResult.java
git commit -m "feat(staticanalysis): domain records (roles, branches, calls, analyses)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `ClassRoleClassifier`

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/ClassRoleClassifier.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/ClassRoleClassifierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassRoleClassifierTest {

    @Test
    void rest_controller_annotation_marks_controller() {
        ClassOrInterfaceDeclaration c = parseType("@RestController class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.CONTROLLER);
    }

    @Test
    void plain_controller_annotation_marks_controller() {
        ClassOrInterfaceDeclaration c = parseType("@Controller class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.CONTROLLER);
    }

    @Test
    void service_annotation_marks_service() {
        ClassOrInterfaceDeclaration c = parseType("@Service class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.SERVICE);
    }

    @Test
    void repository_annotation_marks_repository() {
        ClassOrInterfaceDeclaration c = parseType("@Repository class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.REPOSITORY);
    }

    @Test
    void mapper_annotation_marks_repository() {
        ClassOrInterfaceDeclaration c = parseType("@Mapper class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.REPOSITORY);
    }

    @Test
    void jpa_repository_extends_marks_repository() {
        ClassOrInterfaceDeclaration c = parseType(
                "interface A extends JpaRepository<X,Integer> {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.REPOSITORY);
    }

    @Test
    void crud_repository_extends_marks_repository() {
        ClassOrInterfaceDeclaration c = parseType(
                "interface A extends CrudRepository<X,Integer> {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.REPOSITORY);
    }

    @Test
    void entity_annotation_marks_domain() {
        ClassOrInterfaceDeclaration c = parseType("@Entity class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.DOMAIN);
    }

    @Test
    void embeddable_annotation_marks_domain() {
        ClassOrInterfaceDeclaration c = parseType("@Embeddable class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.DOMAIN);
    }

    @Test
    void mapped_superclass_annotation_marks_domain() {
        ClassOrInterfaceDeclaration c = parseType("@MappedSuperclass class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.DOMAIN);
    }

    @Test
    void no_recognizable_annotation_is_other() {
        ClassOrInterfaceDeclaration c = parseType("class A {}");
        assertThat(ClassRoleClassifier.classify(c)).isEqualTo(ClassRole.OTHER);
    }

    private static ClassOrInterfaceDeclaration parseType(String src) {
        return StaticJavaParser.parse(src).findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.ClassRoleClassifierTest"
```
Expected: BUILD FAILED — `cannot find symbol  symbol: class ClassRoleClassifier`.

- [ ] **Step 3: Implement `ClassRoleClassifier`**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.Set;

/**
 * Maps a {@link ClassOrInterfaceDeclaration} to a {@link ClassRole} using only
 * annotation simple names and {@code extends}/{@code implements} simple names
 * — no FQN resolution required. This matches the conservative approach in
 * {@code path-discovery-static.MappingAnnotation} so behavior stays predictable
 * even when classpath jars are not provided to the symbol solver.
 */
public final class ClassRoleClassifier {

    private static final Set<String> CONTROLLER_ANNOTATIONS =
            Set.of("RestController", "Controller");

    private static final Set<String> SERVICE_ANNOTATIONS =
            Set.of("Service");

    private static final Set<String> REPOSITORY_ANNOTATIONS =
            Set.of("Repository", "Mapper");

    private static final Set<String> DOMAIN_ANNOTATIONS =
            Set.of("Entity", "Embeddable", "MappedSuperclass");

    private static final Set<String> REPOSITORY_BASE_INTERFACES =
            Set.of("JpaRepository", "CrudRepository", "PagingAndSortingRepository", "Repository");

    private ClassRoleClassifier() {}

    public static ClassRole classify(ClassOrInterfaceDeclaration cls) {
        // Order matters: a class with both @Service and @Repository would be ambiguous;
        // we resolve by ranking CONTROLLER > SERVICE > REPOSITORY > DOMAIN > OTHER. This
        // matches typical Spring layering and is documented in the class javadoc above.
        for (AnnotationExpr ann : cls.getAnnotations()) {
            String name = ann.getNameAsString();
            if (CONTROLLER_ANNOTATIONS.contains(name)) return ClassRole.CONTROLLER;
        }
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (SERVICE_ANNOTATIONS.contains(ann.getNameAsString())) return ClassRole.SERVICE;
        }
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (REPOSITORY_ANNOTATIONS.contains(ann.getNameAsString())) return ClassRole.REPOSITORY;
        }
        for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
            if (REPOSITORY_BASE_INTERFACES.contains(ext.getNameAsString())) return ClassRole.REPOSITORY;
        }
        for (ClassOrInterfaceType imp : cls.getImplementedTypes()) {
            if (REPOSITORY_BASE_INTERFACES.contains(imp.getNameAsString())) return ClassRole.REPOSITORY;
        }
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (DOMAIN_ANNOTATIONS.contains(ann.getNameAsString())) return ClassRole.DOMAIN;
        }
        return ClassRole.OTHER;
    }
}
```

- [ ] **Step 4: Run test to verify all pass**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.ClassRoleClassifierTest"
```
Expected: 11 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/ClassRoleClassifier.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/ClassRoleClassifierTest.java
git commit -m "feat(staticanalysis): ClassRoleClassifier — controller/service/repository/domain mapping

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `BranchExtractor`

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/BranchExtractor.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/BranchExtractorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BranchExtractorTest {

    @Test
    void if_statement_extracted_as_branch_with_condition_and_line() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int x) {
                    if (x > 0) { return 1; }
                    return 0;
                }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(1);
        Branch b = branches.get(0);
        assertThat(b.kind()).isEqualTo(BranchKind.IF);
        assertThat(b.condition()).isEqualTo("x > 0");
        assertThat(b.id()).startsWith("demo.C#m:line");
        assertThat(b.lineNumber()).isPositive();
        assertThat(b.referencedVariables()).containsExactly("x");
    }

    @Test
    void nested_if_surfaced_as_separate_branches() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int x, int y) {
                    if (x > 0) {
                        if (y < 0) { return -1; }
                    }
                    return 0;
                }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(2);
        assertThat(branches).extracting(Branch::condition).containsExactly("x > 0", "y < 0");
        // Determinism: ordered by line.
        assertThat(branches.get(0).lineNumber()).isLessThan(branches.get(1).lineNumber());
    }

    @Test
    void switch_statement_extracted_with_selector_as_condition() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int x) {
                    switch (x) {
                        case 1: return 1;
                        case 2: return 2;
                        default: return 0;
                    }
                }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).kind()).isEqualTo(BranchKind.SWITCH);
        assertThat(branches.get(0).condition()).isEqualTo("x");
    }

    @Test
    void ternary_expression_extracted() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int x) { return x > 0 ? 1 : -1; }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).kind()).isEqualTo(BranchKind.TERNARY);
        assertThat(branches.get(0).condition()).isEqualTo("x > 0");
    }

    @Test
    void throw_statement_extracted_with_empty_condition() {
        MethodDeclaration m = parseMethod("""
            class C {
                void m() { throw new RuntimeException("boom"); }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).kind()).isEqualTo(BranchKind.THROW);
        assertThat(branches.get(0).condition()).isEmpty();
    }

    @Test
    void referenced_variables_deduplicated_and_sorted() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int a, int b) {
                    if (a + b > 0 && a < 100) { return 1; }
                    return 0;
                }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches.get(0).referencedVariables()).containsExactly("a", "b");
    }

    @Test
    void empty_method_yields_no_branches() {
        MethodDeclaration m = parseMethod("class C { void m() {} }");
        assertThat(BranchExtractor.extract(m, "demo.C")).isEmpty();
    }

    private static MethodDeclaration parseMethod(String src) {
        return StaticJavaParser.parse(src).findFirst(MethodDeclaration.class).orElseThrow();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.BranchExtractorTest"
```
Expected: BUILD FAILED — `cannot find symbol  symbol: class BranchExtractor`.

- [ ] **Step 3: Implement `BranchExtractor`**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Extracts control-flow branches from a single method body.
 *
 * <p>For each handled AST node, emits a {@link Branch} with:
 * <ul>
 *   <li>{@code id}     — {@code "{classFqn}#{method}:line{N}"}</li>
 *   <li>{@code kind}   — see {@link BranchKind}</li>
 *   <li>{@code condition} — raw source text of the condition (or selector for SWITCH;
 *       empty string for THROW since there is no condition expression on the node itself)</li>
 *   <li>{@code lineNumber}          — 1-based source line</li>
 *   <li>{@code referencedVariables} — deduplicated, alphabetically sorted identifiers
 *       from the condition</li>
 * </ul>
 *
 * <p>Result is ordered by {@code lineNumber} for determinism.
 */
public final class BranchExtractor {

    private BranchExtractor() {}

    public static List<Branch> extract(MethodDeclaration method, String classFqn) {
        List<Branch> out = new ArrayList<>();
        String methodName = method.getNameAsString();

        method.findAll(IfStmt.class).forEach(stmt -> {
            String cond = stmt.getCondition().toString();
            out.add(new Branch(
                    id(classFqn, methodName, lineOf(stmt)),
                    BranchKind.IF,
                    cond,
                    lineOf(stmt),
                    referencedVariables(stmt.getCondition())));
        });

        method.findAll(SwitchStmt.class).forEach(stmt -> {
            String selector = stmt.getSelector().toString();
            out.add(new Branch(
                    id(classFqn, methodName, lineOf(stmt)),
                    BranchKind.SWITCH,
                    selector,
                    lineOf(stmt),
                    referencedVariables(stmt.getSelector())));
        });

        method.findAll(ConditionalExpr.class).forEach(expr -> {
            String cond = expr.getCondition().toString();
            out.add(new Branch(
                    id(classFqn, methodName, lineOf(expr)),
                    BranchKind.TERNARY,
                    cond,
                    lineOf(expr),
                    referencedVariables(expr.getCondition())));
        });

        method.findAll(ThrowStmt.class).forEach(stmt -> {
            out.add(new Branch(
                    id(classFqn, methodName, lineOf(stmt)),
                    BranchKind.THROW,
                    "",
                    lineOf(stmt),
                    List.of()));
        });

        out.sort(Comparator.comparingInt(Branch::lineNumber));
        return out;
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(0);
    }

    private static String id(String classFqn, String methodName, int line) {
        return classFqn + "#" + methodName + ":line" + line;
    }

    private static List<String> referencedVariables(com.github.javaparser.ast.Node node) {
        // TreeSet gives dedup + alphabetical ordering for free.
        TreeSet<String> names = new TreeSet<>();
        node.findAll(NameExpr.class).forEach(ne -> names.add(ne.getNameAsString()));
        return List.copyOf(names);
    }
}
```

- [ ] **Step 4: Run tests to verify all pass**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.BranchExtractorTest"
```
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/BranchExtractor.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/BranchExtractorTest.java
git commit -m "feat(staticanalysis): BranchExtractor — IF/SWITCH/TERNARY/THROW extraction with refs

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `CallGraphBuilder` (+ `CallGraph` integration)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/CallGraphBuilder.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/CallGraphBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import io.graphrag.builder.staticanalysis.ast.SymbolResolverFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CallGraphBuilderTest {

    @Test
    void resolved_in_project_call_recorded(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("demo"));
        Path src = tmp.resolve("demo/C.java");
        Files.writeString(src, """
            package demo;
            class C {
                int add(int a, int b) { return a + b; }
                int caller() { return add(1, 2); }
            }
            """);

        CallGraphBuilder b = new CallGraphBuilder(tmp);
        MethodDeclaration caller = methodNamed(b.parser().parse(src).getResult().orElseThrow(), "caller");

        List<MethodCall> calls = b.outgoingCalls(caller);

        assertThat(calls).hasSize(1);
        MethodCall mc = calls.get(0);
        assertThat(mc.calleeMethodName()).isEqualTo("add");
        assertThat(mc.resolved()).isTrue();
        assertThat(mc.calleeClassFqn()).isEqualTo("demo.C");
    }

    @Test
    void unresolved_call_recorded_as_unresolved(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("demo"));
        Path src = tmp.resolve("demo/D.java");
        Files.writeString(src, """
            package demo;
            class D { void caller() { com.example.Unknown.invoke(); } }
            """);

        CallGraphBuilder b = new CallGraphBuilder(tmp);
        MethodDeclaration caller = methodNamed(b.parser().parse(src).getResult().orElseThrow(), "caller");

        List<MethodCall> calls = b.outgoingCalls(caller);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).resolved()).isFalse();
        assertThat(calls.get(0).calleeMethodName()).isEqualTo("invoke");
        assertThat(calls.get(0).calleeClassFqn()).isNull();
    }

    @Test
    void external_resolved_call_excluded_from_edges(@TempDir Path tmp) throws Exception {
        // String#length is resolvable via ReflectionTypeSolver but is not "in project".
        Files.createDirectories(tmp.resolve("demo"));
        Path src = tmp.resolve("demo/E.java");
        Files.writeString(src, """
            package demo;
            class E { int caller(String s) { return s.length(); } }
            """);

        CallGraphBuilder b = new CallGraphBuilder(tmp);
        CompilationUnit cu = b.parser().parse(src).getResult().orElseThrow();
        Set<String> inProjectClassFqns = Set.of("demo.E");

        CallGraph g = b.build(List.of(cu), inProjectClassFqns);

        // demo.E#caller exists as a key but has no in-project edges.
        assertThat(g.edges()).containsKey("demo.E#caller");
        assertThat(g.edges().get("demo.E#caller")).isEmpty();
    }

    @Test
    void cycle_records_both_directions(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("demo"));
        Path src = tmp.resolve("demo/Cycle.java");
        Files.writeString(src, """
            package demo;
            class Cycle {
                void a() { b(); }
                void b() { a(); }
            }
            """);

        CallGraphBuilder b = new CallGraphBuilder(tmp);
        CompilationUnit cu = b.parser().parse(src).getResult().orElseThrow();
        CallGraph g = b.build(List.of(cu), Set.of("demo.Cycle"));

        assertThat(g.edges().get("demo.Cycle#a")).containsExactly("demo.Cycle#b");
        assertThat(g.edges().get("demo.Cycle#b")).containsExactly("demo.Cycle#a");
    }

    private static MethodDeclaration methodNamed(CompilationUnit cu, String name) {
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(name))
                .findFirst().orElseThrow();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.CallGraphBuilderTest"
```
Expected: BUILD FAILED — `cannot find symbol  symbol: class CallGraphBuilder`.

- [ ] **Step 3: Implement `CallGraphBuilder`**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import io.graphrag.builder.staticanalysis.ast.SymbolResolverFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds {@link CallGraph} edges by walking {@link MethodCallExpr} nodes in each
 * supplied method and attempting symbol resolution against the source root +
 * any classpath jars provided to the underlying {@link JavaParser}.
 *
 * <p>An unresolved call produces a {@link MethodCall} with
 * {@code resolved=false, calleeClassFqn=null}, useful for downstream
 * manual-review reporting in Stage 3.
 *
 * <p>{@link #build(List, Set)} aggregates edges only when the callee class is
 * in the supplied {@code inProjectClassFqns} set — external library calls are
 * excluded from the graph even when their symbol resolves successfully.
 */
public final class CallGraphBuilder {

    private final JavaParser parser;

    public CallGraphBuilder(Path sourceRoot) {
        this(sourceRoot, List.of());
    }

    public CallGraphBuilder(Path sourceRoot, List<Path> classpathJars) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        this.parser = new JavaParser(new ParserConfiguration()
                .setSymbolResolver(SymbolResolverFactory.create(sourceRoot, classpathJars)));
    }

    public JavaParser parser() { return parser; }

    /** Outgoing calls from a single method body, ordered by source line for determinism. */
    public List<MethodCall> outgoingCalls(MethodDeclaration method) {
        List<MethodCall> out = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            int line = call.getRange().map(r -> r.begin.line).orElse(0);
            try {
                ResolvedMethodDeclaration r = call.resolve();
                out.add(new MethodCall(
                        r.declaringType().getQualifiedName(),
                        r.getName(),
                        line,
                        true));
            } catch (Throwable t) {
                out.add(new MethodCall(
                        null,
                        call.getNameAsString(),
                        line,
                        false));
            }
        }
        out.sort(Comparator.comparingInt(MethodCall::line));
        return out;
    }

    /**
     * Aggregate edges across multiple parsed compilation units. Only callees
     * whose resolved declaring class FQN appears in {@code inProjectClassFqns}
     * are recorded; all method keys (including those with no in-project edges)
     * still appear in {@link CallGraph#edges()} so callers can iterate every method.
     */
    public CallGraph build(List<CompilationUnit> units, Set<String> inProjectClassFqns) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            cu.findAll(MethodDeclaration.class).forEach(method -> {
                String classFqn = method.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                        .map(t -> pkg.isEmpty() ? t.getNameAsString() : pkg + "." + t.getNameAsString())
                        .orElse(pkg);
                String key = classFqn + "#" + method.getNameAsString();
                List<String> callees = new ArrayList<>();
                for (MethodCall mc : outgoingCalls(method)) {
                    if (mc.resolved() && inProjectClassFqns.contains(mc.calleeClassFqn())) {
                        callees.add(mc.calleeClassFqn() + "#" + mc.calleeMethodName());
                    }
                }
                edges.put(key, List.copyOf(callees));
            });
        }
        return new CallGraph(edges);
    }
}
```

- [ ] **Step 4: Run tests to verify all pass**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.CallGraphBuilderTest"
```
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/CallGraphBuilder.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/CallGraphBuilderTest.java
git commit -m "feat(staticanalysis): CallGraphBuilder — in-project edges, unresolved-call tolerance

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `EndpointExtractor` — mapping detection + path join

This task only covers the basic mapping → `Endpoint` flow. Auth annotations are added in Task 10 to keep each step focused.

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/EndpointExtractor.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/EndpointExtractorTest.java`

- [ ] **Step 1: Write the failing tests (mapping basics)**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointExtractorTest {

    @Test
    void get_mapping_with_class_prefix_joined() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            @RequestMapping("/owners")
            class C {
                @GetMapping("/{id}")
                Object find(@PathVariable Long id) { return null; }
            }
            """);
        List<Endpoint> eps = EndpointExtractor.extract(cls, "demo.C", "petclinic");
        assertThat(eps).hasSize(1);
        Endpoint ep = eps.get(0);
        assertThat(ep.id()).isEqualTo("GET:/owners/{id}");
        assertThat(ep.method()).isEqualTo(HttpMethod.GET);
        assertThat(ep.path()).isEqualTo("/owners/{id}");
        assertThat(ep.handlerClass()).isEqualTo("demo.C");
        assertThat(ep.handlerMethod()).isEqualTo("find");
        assertThat(ep.project()).isEqualTo("petclinic");
        assertThat(ep.authRequired()).isFalse();
        assertThat(ep.requiredRoles()).isEmpty();
    }

    @Test
    void each_shorthand_annotation_maps_to_method() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            class C {
                @GetMapping("/g")    Object g() { return null; }
                @PostMapping("/p")   Object p() { return null; }
                @PutMapping("/u")    Object u() { return null; }
                @DeleteMapping("/d") Object d() { return null; }
                @PatchMapping("/x")  Object x() { return null; }
            }
            """);
        // The class isn't annotated as a controller; force-process to test mapping logic only.
        List<Endpoint> eps = EndpointExtractor.extractFromClass(cls, "demo.C", "p");
        assertThat(eps).extracting(Endpoint::id)
                .containsExactlyInAnyOrder(
                        "GET:/g", "POST:/p", "PUT:/u", "DELETE:/d", "PATCH:/x");
    }

    @Test
    void request_mapping_with_method_attribute() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            class C {
                @RequestMapping(value = "/r", method = RequestMethod.POST)
                Object r() { return null; }
            }
            """);
        List<Endpoint> eps = EndpointExtractor.extractFromClass(cls, "demo.C", "p");
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).method()).isEqualTo(HttpMethod.POST);
        assertThat(eps.get(0).path()).isEqualTo("/r");
    }

    @Test
    void request_mapping_without_method_is_skipped() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            class C {
                @RequestMapping("/no-method")
                Object x() { return null; }
            }
            """);
        assertThat(EndpointExtractor.extractFromClass(cls, "demo.C", "p")).isEmpty();
    }

    @Test
    void only_controller_annotated_classes_emit_endpoints_via_extract() {
        ClassOrInterfaceDeclaration controller = parseClass("""
            @RestController
            class A { @GetMapping("/a") Object a() { return null; } }
            """);
        ClassOrInterfaceDeclaration service = parseClass("""
            @Service
            class B { @GetMapping("/b") Object b() { return null; } }
            """);
        assertThat(EndpointExtractor.extract(controller, "demo.A", "p")).hasSize(1);
        assertThat(EndpointExtractor.extract(service,    "demo.B", "p")).isEmpty();
    }

    @Test
    void leading_slash_normalized_into_path() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C { @GetMapping("vets") Object v() { return null; } }
            """);
        List<Endpoint> eps = EndpointExtractor.extract(cls, "demo.C", "p");
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).path()).isEqualTo("/vets");
    }

    private static ClassOrInterfaceDeclaration parseClass(String src) {
        return StaticJavaParser.parse(src).findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.EndpointExtractorTest"
```
Expected: BUILD FAILED — `cannot find symbol  symbol: class EndpointExtractor`.

- [ ] **Step 3: Implement `EndpointExtractor` (basic mapping flow only — auth is Task 10)**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts {@link Endpoint}s from a Spring controller class.
 *
 * <p>Only annotation simple names are inspected — Symbol resolution is not
 * required, which keeps behavior predictable when the source tree is parsed
 * without external classpath jars.
 *
 * <p>Public entry point {@link #extract} skips non-controller classes (per
 * {@link ClassRoleClassifier#classify}). The lower-level
 * {@link #extractFromClass} bypasses the role check and is intended for unit
 * testing of the mapping logic in isolation.
 */
public final class EndpointExtractor {

    private static final Map<String, HttpMethod> SHORTHAND = Map.of(
            "GetMapping",    HttpMethod.GET,
            "PostMapping",   HttpMethod.POST,
            "PutMapping",    HttpMethod.PUT,
            "DeleteMapping", HttpMethod.DELETE,
            "PatchMapping",  HttpMethod.PATCH);

    private EndpointExtractor() {}

    public static List<Endpoint> extract(ClassOrInterfaceDeclaration cls,
                                         String classFqn, String project) {
        if (ClassRoleClassifier.classify(cls) != ClassRole.CONTROLLER) {
            return List.of();
        }
        return extractFromClass(cls, classFqn, project);
    }

    /** Visible for tests: applies endpoint-extraction logic ignoring the class role. */
    public static List<Endpoint> extractFromClass(ClassOrInterfaceDeclaration cls,
                                                  String classFqn, String project) {
        String basePath = classBasePath(cls);
        List<Endpoint> out = new ArrayList<>();
        for (MethodDeclaration m : cls.getMethods()) {
            Optional<Mapping> mapping = findMapping(m);
            if (mapping.isEmpty()) continue;
            String fullPath = normalize(basePath + normalize(mapping.get().path));
            if (fullPath.isEmpty()) fullPath = "/";
            String methodName = m.getNameAsString();
            out.add(new Endpoint(
                    mapping.get().method + ":" + fullPath,
                    mapping.get().method,
                    fullPath,
                    project,
                    classFqn,
                    methodName,
                    /* authRequired */ false,
                    /* requiredRoles */ List.of()));
        }
        return out;
    }

    private static String classBasePath(ClassOrInterfaceDeclaration cls) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if ("RequestMapping".equals(ann.getNameAsString())) {
                String p = readPathAttribute(ann);
                if (!p.isEmpty()) return normalize(p);
            }
        }
        return "";
    }

    private static Optional<Mapping> findMapping(MethodDeclaration m) {
        for (AnnotationExpr ann : m.getAnnotations()) {
            String name = ann.getNameAsString();
            if (SHORTHAND.containsKey(name)) {
                return Optional.of(new Mapping(SHORTHAND.get(name), readPathAttribute(ann)));
            }
            if ("RequestMapping".equals(name) && ann instanceof NormalAnnotationExpr na) {
                Optional<HttpMethod> hm = readMethodAttribute(na);
                if (hm.isEmpty()) continue;       // RequestMapping without method= isn't a route
                return Optional.of(new Mapping(hm.get(), readPathAttribute(ann)));
            }
        }
        return Optional.empty();
    }

    /**
     * Read {@code value="..."} / {@code path="..."} / single-string element from any
     * mapping annotation form. Returns "" if not present. Only the first string is
     * honored — Spring's array form picks the first declared route.
     */
    private static String readPathAttribute(AnnotationExpr ann) {
        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr s) {
            return unquote(s.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                String key = pair.getNameAsString();
                if ("value".equals(key) || "path".equals(key)) {
                    return firstStringValue(pair.getValue().toString());
                }
            }
        }
        return "";
    }

    private static Optional<HttpMethod> readMethodAttribute(NormalAnnotationExpr ann) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (!"method".equals(pair.getNameAsString())) continue;
            String raw = pair.getValue().toString();
            int dot = raw.lastIndexOf('.');
            String tok = dot >= 0 ? raw.substring(dot + 1) : raw;
            try {
                return Optional.of(HttpMethod.valueOf(tok));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Extract the first quoted string from either a literal or a {@code {"a","b"}} array. */
    private static String firstStringValue(String raw) {
        String s = raw.trim();
        if (s.startsWith("{")) {
            int start = s.indexOf('"');
            int end = s.indexOf('"', start + 1);
            if (start < 0 || end < 0) return "";
            return s.substring(start + 1, end);
        }
        return unquote(s);
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        String r = s.startsWith("/") ? s : "/" + s;
        while (r.length() > 1 && r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private record Mapping(HttpMethod method, String path) {}
}
```

- [ ] **Step 4: Run tests to verify all pass**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.EndpointExtractorTest"
```
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/EndpointExtractor.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/EndpointExtractorTest.java
git commit -m "feat(staticanalysis): EndpointExtractor — mapping detection + path join

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: `EndpointExtractor` — auth annotations

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/EndpointExtractor.java`
- Modify: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/EndpointExtractorTest.java`

- [ ] **Step 1: Append failing auth tests to `EndpointExtractorTest.java`**

Insert these test methods inside the `EndpointExtractorTest` class, just before the closing brace (after the existing `leading_slash_normalized_into_path` test, before `parseClass`):

```java
    @Test
    void pre_authorize_hasrole_extracts_role() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/admin")
                @PreAuthorize("hasRole('ADMIN')")
                Object a() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.authRequired()).isTrue();
        assertThat(ep.requiredRoles()).containsExactly("ADMIN");
    }

    @Test
    void pre_authorize_hasanyrole_extracts_all_roles() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.requiredRoles()).containsExactly("ADMIN", "USER");
    }

    @Test
    void pre_authorize_is_authenticated_marks_auth_required_with_no_roles() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @PreAuthorize("isAuthenticated()")
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.authRequired()).isTrue();
        assertThat(ep.requiredRoles()).isEmpty();
    }

    @Test
    void secured_strips_role_prefix() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @Secured({"ROLE_ADMIN", "ROLE_USER"})
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.requiredRoles()).containsExactly("ADMIN", "USER");
    }

    @Test
    void roles_allowed_extracts_roles() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @RolesAllowed({"USER"})
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.requiredRoles()).containsExactly("USER");
    }

    @Test
    void class_level_auth_propagates_to_all_methods() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            @PreAuthorize("hasRole('ADMIN')")
            class C {
                @GetMapping("/x") Object x() { return null; }
                @GetMapping("/y") Object y() { return null; }
            }
            """);
        List<Endpoint> eps = EndpointExtractor.extract(cls, "demo.C", "p");
        assertThat(eps).allMatch(Endpoint::authRequired);
        assertThat(eps).allMatch(e -> e.requiredRoles().equals(List.of("ADMIN")));
    }

    @Test
    void unrecognized_spel_yields_no_roles_but_keeps_auth_required() {
        ClassOrInterfaceDeclaration cls = parseClass("""
            @RestController
            class C {
                @GetMapping("/x")
                @PreAuthorize("@bean.check(#root)")
                Object x() { return null; }
            }
            """);
        Endpoint ep = EndpointExtractor.extract(cls, "demo.C", "p").get(0);
        assertThat(ep.authRequired()).isTrue();
        assertThat(ep.requiredRoles()).isEmpty();
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.EndpointExtractorTest"
```
Expected: the 6 previous tests still pass, the 7 new tests fail (auth assertions wrong since `extract` currently hard-codes `false`/`List.of()`).

- [ ] **Step 3: Extend `EndpointExtractor` with auth annotation handling**

In `EndpointExtractor.java`:

(a) Add these imports if not already present:
```java
import com.github.javaparser.ast.NodeList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

(b) Inside the class, add the constants and helper methods (place them after the existing `SHORTHAND` map):

```java
    private static final Set<String> AUTH_ANNOTATIONS =
            Set.of("PreAuthorize", "Secured", "RolesAllowed");

    private static final Pattern HAS_ROLE      = Pattern.compile("hasRole\\(['\"]([^'\"]+)['\"]\\)");
    private static final Pattern HAS_ANY_ROLE  = Pattern.compile("hasAnyRole\\(([^)]+)\\)");
    private static final Pattern ROLE_LITERAL  = Pattern.compile("['\"]([^'\"]+)['\"]");
    private static final String  IS_AUTHENTICATED = "isAuthenticated()";

    private record AuthInfo(boolean authRequired, List<String> roles) {
        static AuthInfo none() { return new AuthInfo(false, List.of()); }
    }
```

Also add this import at the top of the file if missing:
```java
import java.util.Set;
```

(c) Replace the body of `extractFromClass` to merge class-level and method-level auth:

```java
    public static List<Endpoint> extractFromClass(ClassOrInterfaceDeclaration cls,
                                                  String classFqn, String project) {
        String basePath = classBasePath(cls);
        AuthInfo classAuth = readAuth(cls.getAnnotations());
        List<Endpoint> out = new ArrayList<>();
        for (MethodDeclaration m : cls.getMethods()) {
            Optional<Mapping> mapping = findMapping(m);
            if (mapping.isEmpty()) continue;
            String fullPath = normalize(basePath + normalize(mapping.get().path));
            if (fullPath.isEmpty()) fullPath = "/";
            AuthInfo methodAuth = readAuth(m.getAnnotations());
            AuthInfo merged = mergeAuth(classAuth, methodAuth);
            out.add(new Endpoint(
                    mapping.get().method + ":" + fullPath,
                    mapping.get().method,
                    fullPath,
                    project,
                    classFqn,
                    m.getNameAsString(),
                    merged.authRequired(),
                    merged.roles()));
        }
        return out;
    }

    private static AuthInfo mergeAuth(AuthInfo cls, AuthInfo method) {
        if (!cls.authRequired() && !method.authRequired()) return AuthInfo.none();
        // Method-level roles take precedence when present; class roles are the default.
        if (!method.roles().isEmpty()) return new AuthInfo(true, method.roles());
        if (!cls.roles().isEmpty())    return new AuthInfo(true, cls.roles());
        return new AuthInfo(true, List.of());
    }

    private static AuthInfo readAuth(NodeList<AnnotationExpr> annotations) {
        boolean required = false;
        List<String> roles = List.of();
        for (AnnotationExpr ann : annotations) {
            String name = ann.getNameAsString();
            if (!AUTH_ANNOTATIONS.contains(name)) continue;
            required = true;
            switch (name) {
                case "PreAuthorize" -> roles = parsePreAuthorizeRoles(rawSpel(ann));
                case "Secured"      -> roles = parseSecuredRoles(ann);
                case "RolesAllowed" -> roles = parseRolesAllowed(ann);
            }
            if (!roles.isEmpty()) break;     // first auth annotation with roles wins
        }
        return new AuthInfo(required, roles);
    }

    private static String rawSpel(AnnotationExpr ann) {
        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr s) {
            return unquote(s.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    return unquote(pair.getValue().toString());
                }
            }
        }
        return "";
    }

    private static List<String> parsePreAuthorizeRoles(String spel) {
        if (spel.equals(IS_AUTHENTICATED)) return List.of();
        Matcher hr = HAS_ROLE.matcher(spel);
        if (hr.find()) return List.of(hr.group(1));
        Matcher har = HAS_ANY_ROLE.matcher(spel);
        if (har.find()) {
            List<String> roles = new ArrayList<>();
            Matcher lit = ROLE_LITERAL.matcher(har.group(1));
            while (lit.find()) roles.add(lit.group(1));
            return List.copyOf(roles);
        }
        return List.of();
    }

    private static List<String> parseSecuredRoles(AnnotationExpr ann) {
        List<String> raw = readStringArray(ann);
        List<String> stripped = new ArrayList<>(raw.size());
        for (String r : raw) {
            stripped.add(r.startsWith("ROLE_") ? r.substring("ROLE_".length()) : r);
        }
        return List.copyOf(stripped);
    }

    private static List<String> parseRolesAllowed(AnnotationExpr ann) {
        return readStringArray(ann);
    }

    private static List<String> readStringArray(AnnotationExpr ann) {
        String raw = "";
        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr s) {
            raw = s.getMemberValue().toString();
        } else if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                if ("value".equals(pair.getNameAsString())) raw = pair.getValue().toString();
            }
        }
        if (raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = ROLE_LITERAL.matcher(raw);
        while (m.find()) out.add(m.group(1));
        return List.copyOf(out);
    }
```

- [ ] **Step 4: Run tests to verify all pass**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.EndpointExtractorTest"
```
Expected: 13 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/EndpointExtractor.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/EndpointExtractorTest.java
git commit -m "feat(staticanalysis): EndpointExtractor auth — @PreAuthorize/@Secured/@RolesAllowed

Class-level auth annotations propagate to all methods; method-level roles
take precedence over class-level. ROLE_ prefix stripped per work-order §7.4.3.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: `DomainAnalyzer` orchestrator

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzer.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.graphrag.builder.staticanalysis.domain;

import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainAnalyzerTest {

    @Test
    void analyzes_simple_controller_and_classifies_neighbors(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("demo"));
        Files.writeString(root.resolve("demo/OwnerController.java"), """
            package demo;
            @RestController @RequestMapping("/owners")
            class OwnerController {
                @GetMapping("/{id}") Object find(@PathVariable Long id) { return null; }
                @PostMapping        Object create(@RequestBody Object body) { return null; }
            }
            """);
        Files.writeString(root.resolve("demo/OwnerService.java"), """
            package demo;
            @Service class OwnerService {}
            """);
        Files.writeString(root.resolve("demo/OwnerRepository.java"), """
            package demo;
            interface OwnerRepository extends JpaRepository<Object, Long> {}
            """);
        Files.writeString(root.resolve("demo/Owner.java"), """
            package demo;
            @Entity class Owner {}
            """);

        AstParseResult ast = AstParser.parse(root);
        DomainAnalysisResult r = DomainAnalyzer.analyze(ast, "demo-project");

        assertThat(r.endpoints()).extracting(Endpoint::id)
                .containsExactly("GET:/owners/{id}", "POST:/owners");
        assertThat(r.classRoles())
                .containsEntry("demo.OwnerController", ClassRole.CONTROLLER)
                .containsEntry("demo.OwnerService",    ClassRole.SERVICE)
                .containsEntry("demo.OwnerRepository", ClassRole.REPOSITORY)
                .containsEntry("demo.Owner",           ClassRole.DOMAIN);
        assertThat(r.endpoints()).allMatch(e -> "demo-project".equals(e.project()));
    }

    @Test
    void endpoints_sorted_by_path_then_method(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("p/C.java"), """
            package p;
            @RestController
            class C {
                @PostMapping("/b")  Object pb() { return null; }
                @GetMapping("/a")   Object ga() { return null; }
                @DeleteMapping("/a") Object da() { return null; }
                @GetMapping("/c")   Object gc() { return null; }
            }
            """);

        DomainAnalysisResult r = DomainAnalyzer.analyze(AstParser.parse(root), "p");

        assertThat(r.endpoints()).extracting(Endpoint::id)
                .containsExactly("DELETE:/a", "GET:/a", "GET:/c", "POST:/b");
    }

    @Test
    void method_analyses_populated_for_controller_service_repository_only(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("p/Ctrl.java"), """
            package p;
            @RestController class Ctrl { @GetMapping("/x") Object x() { if (true) return null; return null; } }
            """);
        Files.writeString(root.resolve("p/Dom.java"), """
            package p;
            @Entity class Dom { int field; int getField() { return field; } }
            """);

        DomainAnalysisResult r = DomainAnalyzer.analyze(AstParser.parse(root), "p");

        assertThat(r.methodAnalyses()).containsKey("p.Ctrl#x");
        assertThat(r.methodAnalyses()).doesNotContainKey("p.Dom#getField");
    }

    @Test
    void analyze_is_deterministic(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("p/C.java"), """
            package p;
            @RestController class C { @GetMapping("/a") Object a() { return null; } }
            """);

        DomainAnalysisResult r1 = DomainAnalyzer.analyze(AstParser.parse(root), "p");
        DomainAnalysisResult r2 = DomainAnalyzer.analyze(AstParser.parse(root), "p");

        assertThat(r1.endpoints()).isEqualTo(r2.endpoints());
        assertThat(r1.classRoles().keySet()).containsExactlyElementsOf(r2.classRoles().keySet());
        assertThat(r1.methodAnalyses().keySet()).containsExactlyElementsOf(r2.methodAnalyses().keySet());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.DomainAnalyzerTest"
```
Expected: BUILD FAILED — `cannot find symbol  symbol: class DomainAnalyzer`.

- [ ] **Step 3: Implement `DomainAnalyzer`**

```java
package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.ParsedFile;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 2 orchestrator: turn an {@link AstParseResult} into a
 * {@link DomainAnalysisResult}. Pure function in/out — no I/O.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Classify every top-level type via {@link ClassRoleClassifier}.</li>
 *   <li>For every {@code CONTROLLER}, extract {@link Endpoint}s via {@link EndpointExtractor}.</li>
 *   <li>For every {@code CONTROLLER}, {@code SERVICE}, {@code REPOSITORY} method, build a
 *       {@link MethodAnalysis} (parameters + branches + outgoing calls + return type).</li>
 *   <li>Build the in-project {@link CallGraph} across all parsed compilation units.</li>
 * </ol>
 *
 * <p>{@link DomainAnalysisResult#endpoints()} is sorted by {@code (path, method)};
 * {@link DomainAnalysisResult#classRoles()} and {@link DomainAnalysisResult#methodAnalyses()}
 * are insertion-ordered (insertion = path-sorted ParsedFile order).
 */
public final class DomainAnalyzer {

    private static final Set<ClassRole> METHOD_ROLES_OF_INTEREST =
            Set.of(ClassRole.CONTROLLER, ClassRole.SERVICE, ClassRole.REPOSITORY);

    private DomainAnalyzer() {}

    public static DomainAnalysisResult analyze(AstParseResult ast, String project) {
        // 1) class roles (insertion-ordered).
        Map<String, ClassRole> classRoles = new LinkedHashMap<>();
        Map<String, ClassOrInterfaceDeclaration> classByFqn = new LinkedHashMap<>();
        for (ParsedFile pf : ast.parsedFiles()) {
            pf.cu().findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String fqn = pf.packageName().isEmpty()
                        ? cls.getNameAsString()
                        : pf.packageName() + "." + cls.getNameAsString();
                classRoles.put(fqn, ClassRoleClassifier.classify(cls));
                classByFqn.put(fqn, cls);
            });
        }

        // 2) endpoints (collected then sorted).
        List<Endpoint> endpoints = new ArrayList<>();
        classByFqn.forEach((fqn, cls) -> {
            if (classRoles.get(fqn) == ClassRole.CONTROLLER) {
                endpoints.addAll(EndpointExtractor.extract(cls, fqn, project));
            }
        });
        endpoints.sort(Comparator
                .comparing(Endpoint::path)
                .thenComparing((Endpoint e) -> e.method().name()));

        // 3) method analyses.
        Map<String, MethodAnalysis> methodAnalyses = new LinkedHashMap<>();
        classByFqn.forEach((fqn, cls) -> {
            if (!METHOD_ROLES_OF_INTEREST.contains(classRoles.get(fqn))) return;
            for (MethodDeclaration m : cls.getMethods()) {
                MethodAnalysis ma = buildMethodAnalysis(fqn, m);
                methodAnalyses.put(ma.key(), ma);
            }
        });

        // 4) call graph — build over all CUs but restrict edges to in-project classes.
        CallGraphBuilder cgb = new CallGraphBuilder(rootOf(ast));
        CallGraph callGraph = cgb.build(
                ast.parsedFiles().stream().map(ParsedFile::cu).toList(),
                classRoles.keySet());

        return new DomainAnalysisResult(endpoints, classRoles, methodAnalyses, callGraph);
    }

    private static MethodAnalysis buildMethodAnalysis(String classFqn, MethodDeclaration m) {
        List<io.graphrag.builder.staticanalysis.domain.Parameter> params = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            List<String> annNames = p.getAnnotations().stream()
                    .map(a -> a.getNameAsString()).toList();
            params.add(new io.graphrag.builder.staticanalysis.domain.Parameter(
                    p.getNameAsString(),
                    p.getTypeAsString(),
                    annNames));
        }
        List<Branch> branches = BranchExtractor.extract(m, classFqn);
        // outgoingCalls populated by CallGraphBuilder when the per-method view is needed;
        // for DomainAnalyzer's purposes we leave it empty here and let downstream consumers
        // query CallGraph.edges() by key — keeps MethodAnalysis cheap to build.
        ReturnType rt = ReturnType.of(m.getTypeAsString());
        return new MethodAnalysis(
                classFqn,
                m.getNameAsString(),
                params,
                branches,
                /* outgoingCalls */ List.of(),
                rt);
    }

    /**
     * Returns the deepest common parent of all parsed source paths so the
     * SymbolSolver inside {@link CallGraphBuilder} has a meaningful source root.
     * Falls back to {@code Paths.get(".")} when the parsed list is empty.
     */
    private static java.nio.file.Path rootOf(AstParseResult ast) {
        if (ast.parsedFiles().isEmpty()) return java.nio.file.Paths.get(".");
        java.nio.file.Path candidate = ast.parsedFiles().get(0).sourcePath().getParent();
        for (ParsedFile pf : ast.parsedFiles()) {
            while (candidate != null && !pf.sourcePath().startsWith(candidate)) {
                candidate = candidate.getParent();
            }
        }
        return candidate == null ? java.nio.file.Paths.get(".") : candidate;
    }
}
```

> **Reviewer note (to the engineer):** `MethodAnalysis.outgoingCalls` is intentionally
> left empty in this task. The work-order spec keeps `outgoingCalls` on `MethodAnalysis`
> for future use, but populating it requires running the full `CallGraphBuilder.outgoingCalls`
> on each method, which is O(M·C) work that nothing in T2 consumes. T3 will fill it
> when the branch analyzer actually needs it. Tests above do not assert on
> `outgoingCalls` content.

- [ ] **Step 4: Run tests to verify all pass**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.DomainAnalyzerTest"
```
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzerTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): DomainAnalyzer — orchestrates classify → endpoints → methods → call graph

In-memory result only this session. endpoints sorted by (path, method);
classRoles + methodAnalyses are LinkedHashMap in path-sorted insertion
order. MethodAnalysis.outgoingCalls intentionally empty — T3 will fill
it when the branch analyzer needs per-method callees.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Petclinic integration test fixture + test

**Files:**
- Create: `graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/org/example/petclinic/Owner.java`
- Create: `graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/org/example/petclinic/OwnerRepository.java`
- Create: `graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/org/example/petclinic/OwnerService.java`
- Create: `graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/org/example/petclinic/OwnerRestController.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzerPetclinicTest.java`

- [ ] **Step 1: Create the petclinic fixture — `Owner.java`**

Create `graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/org/example/petclinic/Owner.java`:

```java
package org.example.petclinic;

@Entity
public class Owner {
    private Integer id;
    private String firstName;
    private String lastName;

    public Integer getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
}
```

- [ ] **Step 2: Create `OwnerRepository.java`**

```java
package org.example.petclinic;

public interface OwnerRepository extends JpaRepository<Owner, Integer> {
    Owner findByLastName(String lastName);
}
```

- [ ] **Step 3: Create `OwnerService.java`**

```java
package org.example.petclinic;

@Service
public class OwnerService {

    private final OwnerRepository repo;

    public OwnerService(OwnerRepository repo) { this.repo = repo; }

    public Owner find(Integer id) {
        if (id == null) { throw new IllegalArgumentException("id"); }
        if (id < 0)     { throw new IllegalArgumentException("negative id"); }
        return repo.findById(id).orElseThrow();
    }
}
```

- [ ] **Step 4: Create `OwnerRestController.java`**

```java
package org.example.petclinic;

import java.util.List;

@RestController
@RequestMapping("/owners")
public class OwnerRestController {

    private final OwnerService service;

    public OwnerRestController(OwnerService service) { this.service = service; }

    @GetMapping
    public List<Owner> listOwners() { return List.of(); }

    @GetMapping("/{id}")
    public Owner getOwner(@PathVariable Integer id) {
        if (id == null) { return null; }
        return service.find(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Owner createOwner(@RequestBody Owner body) {
        switch (body.getFirstName()) {
            case "": throw new IllegalArgumentException("blank");
            case "ADMIN": throw new IllegalArgumentException("reserved");
            default: return body;
        }
    }

    @PutMapping("/{id}")
    @Secured({"ROLE_ADMIN", "ROLE_USER"})
    public Owner updateOwner(@PathVariable Integer id, @RequestBody Owner body) {
        return body;
    }

    @DeleteMapping("/{id}")
    @RolesAllowed({"ADMIN"})
    public void deleteOwner(@PathVariable Integer id) {}
}
```

- [ ] **Step 5: Write the failing integration test**

Create `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzerPetclinicTest.java`:

```java
package io.graphrag.builder.staticanalysis.domain;

import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DomainAnalyzerPetclinicTest {

    private static final Pattern ID_FORMAT =
            Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS):/.+$");

    @Test
    void analyzes_petclinic_fixture_and_meets_acceptance_criteria() throws Exception {
        Path root = fixtureRoot();
        AstParseResult ast = AstParser.parse(root);
        assertThat(ast.failures()).isEmpty();
        assertThat(ast.parsedFiles()).hasSizeGreaterThanOrEqualTo(4);

        DomainAnalysisResult r = DomainAnalyzer.analyze(ast, "petclinic");

        // ClassRoles
        assertThat(r.classRoles())
                .containsEntry("org.example.petclinic.OwnerRestController", ClassRole.CONTROLLER)
                .containsEntry("org.example.petclinic.OwnerService",        ClassRole.SERVICE)
                .containsEntry("org.example.petclinic.OwnerRepository",     ClassRole.REPOSITORY)
                .containsEntry("org.example.petclinic.Owner",               ClassRole.DOMAIN);

        // Endpoints
        List<String> ids = r.endpoints().stream().map(Endpoint::id).toList();
        assertThat(ids).contains(
                "GET:/owners",
                "GET:/owners/{id}",
                "POST:/owners",
                "PUT:/owners/{id}",
                "DELETE:/owners/{id}");
        assertThat(ids).allMatch(id -> ID_FORMAT.matcher(id).matches());

        // Sorted determinism — same input twice, same order.
        List<String> ids2 = DomainAnalyzer.analyze(AstParser.parse(root), "petclinic")
                .endpoints().stream().map(Endpoint::id).toList();
        assertThat(ids).isEqualTo(ids2);

        // Auth annotations
        Endpoint create = endpointById(r, "POST:/owners");
        assertThat(create.authRequired()).isTrue();
        assertThat(create.requiredRoles()).containsExactly("ADMIN");

        Endpoint update = endpointById(r, "PUT:/owners/{id}");
        assertThat(update.authRequired()).isTrue();
        assertThat(update.requiredRoles()).containsExactly("ADMIN", "USER");

        Endpoint delete = endpointById(r, "DELETE:/owners/{id}");
        assertThat(delete.authRequired()).isTrue();
        assertThat(delete.requiredRoles()).containsExactly("ADMIN");

        Endpoint list = endpointById(r, "GET:/owners");
        assertThat(list.authRequired()).isFalse();

        // MethodAnalysis populated for controller methods
        assertThat(r.methodAnalyses())
                .containsKey("org.example.petclinic.OwnerRestController#listOwners")
                .containsKey("org.example.petclinic.OwnerRestController#getOwner")
                .containsKey("org.example.petclinic.OwnerRestController#createOwner");

        // Branch extraction on getOwner (`if (id == null)`)
        MethodAnalysis getOwner = r.methodAnalyses().get(
                "org.example.petclinic.OwnerRestController#getOwner");
        assertThat(getOwner.branches()).extracting(Branch::kind).contains(BranchKind.IF);

        // Switch + throw in createOwner
        MethodAnalysis createMa = r.methodAnalyses().get(
                "org.example.petclinic.OwnerRestController#createOwner");
        assertThat(createMa.branches()).extracting(Branch::kind)
                .contains(BranchKind.SWITCH, BranchKind.THROW);
    }

    private static Endpoint endpointById(DomainAnalysisResult r, String id) {
        return r.endpoints().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private static Path fixtureRoot() throws Exception {
        URL res = DomainAnalyzerPetclinicTest.class.getClassLoader()
                .getResource("staticanalysis/petclinic-fixture");
        if (res == null) throw new IllegalStateException("fixture missing");
        return Paths.get(res.toURI());
    }
}
```

- [ ] **Step 6: Run the petclinic test**

Run:
```bash
./gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.domain.DomainAnalyzerPetclinicTest"
```
Expected: 1 test PASS. If the source-root inference in `DomainAnalyzer.rootOf` chooses a non-existent directory because the fixture lives under `build/resources/test/...`, the test still passes — `CallGraphBuilder` tolerates a non-source-tree root because `SymbolResolverFactory.create` guards `Files.isDirectory`.

- [ ] **Step 7: Run the full graph-rag-builder test suite**

Make sure none of the existing tests regressed.

Run:
```bash
./gw :graph-rag-builder:test
```
Expected: all tests pass, including pre-existing `JdbcAgentBaggageBridgeTest` (required because we built with `-Pagent.enabled=true`).

- [ ] **Step 8: Commit**

```bash
git add graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzerPetclinicTest.java
git commit -m "$(cat <<'EOF'
test(staticanalysis): petclinic fixture + DomainAnalyzer integration test

Mini-petclinic fixture under src/test/resources covers all four class roles
and exercises class-level @RequestMapping, @PreAuthorize, @Secured,
@RolesAllowed, plus nested if / switch / throw branches. Asserts every
T1+T2 acceptance criterion in one place.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final Verification

After Task 12, run the full repo build (excluding the orchestrator coverage report that already passes) to confirm nothing else broke:

```bash
./gw :shared-model:test :graph-rag-builder:test \
     :path-discovery-static:test :coverage-feedback:test \
     :orchestrator:test :scout-step-translator:test
```

Expected: all modules GREEN.

---

## Self-Review (already applied inline)

- **Spec coverage:** Each acceptance criterion in spec §4.8 and §3.4 is asserted in Tasks 4, 6, 7, 8, 9, 10, 11, or 12.
- **Placeholder scan:** Searched for "TBD", "TODO", "implement later", "appropriate error handling" — none found in the plan body. The single reviewer note in Task 11 about `outgoingCalls` is explicit, not a placeholder.
- **Type consistency:** `MethodAnalysis.key()` is defined in Task 5 and consumed in Task 11. `Branch.id` format matches between `BranchExtractor` (Task 7) and the test asserts. `Endpoint.id` is `{METHOD}:{path}` everywhere.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-28-graph-rag-builder-static-analysis.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
