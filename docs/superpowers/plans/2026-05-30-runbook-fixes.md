# Runbook Follow-ups #1 + #3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close two known limitations from PR #13's runbook so that generated RestAssured tests actually hit a live petclinic (#1) and `@PathVariable("name") Custom owner` paths stop being quarantined (#3) — making cumulative coverage grow across iterations.

**Architecture:** Layered onto existing components, no new modules. (#3) extends `Parameter` with `annotationValues` + teaches `DomainAnalyzer` to fill them via JavaParser + teaches `SampleInputGenerator` to prefer annotation values as the path/query/header param key. (#1) extends `scripts/petclinic-stage5.sh` to launch petclinic before mvn, export `APP_BASE_URI`, and stop it on EXIT.

**Tech Stack:** Java 17 + JavaParser 3.25.10 (already on classpath) + Gradle 8.x for the analyzer changes; Bash + curl + a backgrounded `java -jar` for the wrapper.

**Build flag:** Every gradle invocation in this plan needs `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home` and `-Pagent.enabled=true` (pre-existing repo constraint; see PR #13 runbook §"Known limitations" #4).

---

## File map

**Modified:**
- `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/Parameter.java` — record gets `Map<String, String> annotationValues` field + backward-compat 3-arg constructor
- `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzer.java` — `buildMethodAnalysis` extracts annotation primary values
- `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/SampleInputGenerator.java` — new `paramKey` helper applied to PATH / QUERY / HEADER cases in both `happy` and `boundary`
- `scripts/petclinic-stage5.sh` — `launch_sut` / `stop_sut`, EXIT trap extension, `APP_BASE_URI` exported into mvn env
- `orchestrator/src/test/java/io/graphrag/orchestrator/PetclinicStage5ScriptTest.java` — existing tests get `SKIP_SUT_LAUNCH=1` env; new test asserts `APP_BASE_URI` propagates
- `docs/orchestrator-e2e-petclinic.md` — updated reference-run numbers after the live re-run

**Created:**
- `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzerAnnotationValueTest.java` — covers the four annotation forms

**Untouched (verified, no change needed):**
- `test-generator/src/main/java/io/graphrag/generator/core/TestSynthesizer.java` — already emits `RestAssured.baseURI = System.getenv("APP_BASE_URI")`
- `orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java` — placeholder filter stays as last-line defense
- `samples/scout/petclinic/template.yml` — wrapper now matches launch args via defaults

---

## Task 1: Extend `Parameter` record with `annotationValues`

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/Parameter.java`

- [ ] **Step 1: Edit the record**

Replace the entire file with:

```java
package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A method parameter as seen at the AST level. Annotation simple names are
 * preserved verbatim (e.g. {@code "PathVariable"}, {@code "RequestBody"}).
 *
 * <p>{@code annotationValues} carries the primary value of each annotation, when
 * present. For {@code @PathVariable("ownerId")} or {@code @PathVariable(name = "ownerId")},
 * the entry is {@code "PathVariable" -> "ownerId"}. Marker annotations
 * ({@code @PathVariable} with no arguments) contribute no entry.
 */
public record Parameter(
        String name,
        String type,
        List<String> annotations,
        Map<String, String> annotationValues) {

    public Parameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        annotationValues = Map.copyOf(Objects.requireNonNull(annotationValues, "annotationValues"));
    }

    /**
     * Backward-compatible constructor for sites that don't carry annotation values
     * (mostly tests + legacy code paths). Equivalent to the 4-arg form with an
     * empty annotation-values map.
     */
    public Parameter(String name, String type, List<String> annotations) {
        this(name, type, annotations, Map.of());
    }
}
```

- [ ] **Step 2: Compile-check the whole module**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :graph-rag-builder:compileJava :graph-rag-builder:compileTestJava
```

Expected: BUILD SUCCESSFUL. Existing call sites use the 3-arg constructor and stay compatible.

- [ ] **Step 3: Run the full graph-rag-builder test suite to verify no behavior regressions**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :graph-rag-builder:test
```

Expected: 171 GREEN (3 Neo4j-required skipped). All existing tests construct `Parameter` via the 3-arg shape; the secondary constructor preserves that.

- [ ] **Step 4: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/Parameter.java
git commit -m "feat(staticanalysis): Parameter carries annotationValues for @PathVariable(\"x\") aliasing"
```

---

## Task 2: Failing test for `DomainAnalyzer` annotation-value extraction

**Files:**
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzerAnnotationValueTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.builder.staticanalysis.domain;

import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wiring fix from spec §2.3: DomainAnalyzer must pull each annotation's
 * primary value (single-member literal, normal-annotation `value` pair, normal-
 * annotation `name` pair) into Parameter.annotationValues. Marker annotations
 * contribute no entry.
 */
class DomainAnalyzerAnnotationValueTest {

    @Test
    void capturesPrimaryAnnotationValuesAcrossSpringForms(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java/demo/Ctrl.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/owners")
                class Ctrl {
                    @GetMapping("/{ownerId}/single")
                    public String single(@PathVariable("ownerId") Integer id) { return ""; }

                    @GetMapping("/{ownerId}/value")
                    public String value(@PathVariable(value = "ownerId") Integer id) { return ""; }

                    @GetMapping("/{ownerId}/named")
                    public String named(@PathVariable(name = "ownerId") Integer id) { return ""; }

                    @GetMapping("/{id}/marker")
                    public String marker(@PathVariable Integer id) { return ""; }
                }
                """);

        AstParseResult ast = AstParser.parse(tmp.resolve("src/main/java"));
        DomainAnalysisResult domain = DomainAnalyzer.analyze(ast, "demo");

        MethodAnalysis single = methodNamed(domain, "single");
        MethodAnalysis value  = methodNamed(domain, "value");
        MethodAnalysis named  = methodNamed(domain, "named");
        MethodAnalysis marker = methodNamed(domain, "marker");

        assertThat(single.parameters().get(0).annotationValues())
                .isEqualTo(Map.of("PathVariable", "ownerId"));
        assertThat(value.parameters().get(0).annotationValues())
                .isEqualTo(Map.of("PathVariable", "ownerId"));
        assertThat(named.parameters().get(0).annotationValues())
                .isEqualTo(Map.of("PathVariable", "ownerId"));
        assertThat(marker.parameters().get(0).annotationValues())
                .isEmpty();
    }

    private static MethodAnalysis methodNamed(DomainAnalysisResult d, String name) {
        return d.methodAnalyses().values().stream()
                .filter(m -> name.equals(m.methodName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no MethodAnalysis named '" + name + "' in " + d.methodAnalyses().keySet()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :graph-rag-builder:test \
  --tests io.graphrag.builder.staticanalysis.domain.DomainAnalyzerAnnotationValueTest
```

Expected: FAIL — the asserted maps are empty because `DomainAnalyzer.buildMethodAnalysis` doesn't populate `annotationValues` yet.

- [ ] **Step 3: Commit the failing test**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzerAnnotationValueTest.java
git commit -m "test(staticanalysis): pin DomainAnalyzer annotation-value extraction (RED)"
```

---

## Task 3: Implement `DomainAnalyzer.extractPrimaryAnnotationValue`

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzer.java`

- [ ] **Step 1: Add imports near the existing JavaParser imports**

Find the existing JavaParser imports near the top of the file and add:

```java
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
```

If the file already imports `com.github.javaparser.ast.expr.AnnotationExpr` (likely), only add the `MemberValuePair` import.

- [ ] **Step 2: Update `buildMethodAnalysis` to populate `annotationValues`**

Replace the existing parameter-loop in `buildMethodAnalysis` with:

```java
private static MethodAnalysis buildMethodAnalysis(String classFqn, MethodDeclaration m) {
    List<io.graphrag.builder.staticanalysis.domain.Parameter> params = new ArrayList<>();
    for (Parameter p : m.getParameters()) {
        List<String> annNames = p.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString).toList();
        Map<String, String> annValues = new HashMap<>();
        for (AnnotationExpr a : p.getAnnotations()) {
            String v = extractPrimaryAnnotationValue(a);
            if (v != null) annValues.put(a.getNameAsString(), v);
        }
        params.add(new io.graphrag.builder.staticanalysis.domain.Parameter(
                p.getNameAsString(), p.getTypeAsString(), annNames, annValues));
    }
    List<Branch> branches = BranchExtractor.extract(m, classFqn);
    ReturnType rt = ReturnType.of(m.getTypeAsString());
    return new MethodAnalysis(
            classFqn,
            m.getNameAsString(),
            params,
            // The existing tail (return type + branches + outgoingCalls) stays as-is.
            // Preserve whatever was here previously.
            rt,
            branches,
            java.util.List.of());
}
```

Note: keep the constructor's last three arguments matching whatever `MethodAnalysis`'s actual signature is at HEAD. If the existing code passes `List.of()` for outgoingCalls, keep it; if it passes something else, keep that. Don't change unrelated behavior.

Add the helper at the bottom of the class (just before the closing brace):

```java
private static String extractPrimaryAnnotationValue(AnnotationExpr a) {
    if (a.isSingleMemberAnnotationExpr()) {
        return unquoteStringLiteral(
                a.asSingleMemberAnnotationExpr().getMemberValue().toString());
    }
    if (a.isNormalAnnotationExpr()) {
        for (MemberValuePair pair : a.asNormalAnnotationExpr().getPairs()) {
            String pname = pair.getNameAsString();
            if ("value".equals(pname) || "name".equals(pname)) {
                return unquoteStringLiteral(pair.getValue().toString());
            }
        }
    }
    return null;
}

private static String unquoteStringLiteral(String raw) {
    if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        return raw.substring(1, raw.length() - 1);
    }
    return raw;
}
```

Also add `import java.util.HashMap;` and `import java.util.Map;` near the existing `java.util.*` imports if they aren't already present.

- [ ] **Step 3: Run the targeted test to verify it passes**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :graph-rag-builder:test \
  --tests io.graphrag.builder.staticanalysis.domain.DomainAnalyzerAnnotationValueTest
```

Expected: PASS — four assertions cover single-member, normal/value, normal/name, marker.

- [ ] **Step 4: Run the full graph-rag-builder suite to confirm no regressions**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :graph-rag-builder:test
```

Expected: 171 GREEN (3 Neo4j-required skipped). The `DomainAnalyzerPetclinicTest` and existing `DomainAnalyzerTest` should both pass — annotations behavior is purely additive.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/DomainAnalyzer.java
git commit -m "feat(staticanalysis): extract primary annotation values via JavaParser"
```

---

## Task 4: Failing test for `SampleInputGenerator` annotation-value keying

**Files:**
- Modify: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/SampleInputGeneratorTest.java` (append two test methods)

- [ ] **Step 1: Append the new tests**

Look at the existing file to see the import block and existing test style. Then append (inside the existing class body, after the last `@Test` method):

```java
@Test
void pathVariableWithExplicitNameUsesAnnotationValueAsKey() {
    // @PathVariable("ownerId") Owner owner — the URL placeholder is "ownerId"
    // but the Java parameter name is "owner". The pathParams key must be
    // "ownerId", not "owner", or the orchestrator's placeholder filter
    // will quarantine the path.
    Parameter renamed = new Parameter(
            "owner",
            "Owner",
            java.util.List.of("PathVariable"),
            java.util.Map.of("PathVariable", "ownerId"));
    MethodAnalysis ma = new MethodAnalysis(
            "demo.Ctrl", "show",
            java.util.List.of(renamed),
            io.graphrag.builder.staticanalysis.domain.ReturnType.PRIMITIVE,
            java.util.List.of(),
            java.util.List.of());
    Endpoint ep = new Endpoint("GET:/owners/{ownerId}", io.graphrag.model.HttpMethod.GET,
            "/owners/{ownerId}", "demo", "demo.Ctrl", "show", false, java.util.List.of());

    java.util.List<NamedSampleInput> inputs = SampleInputGenerator.generate(
            ep, ma, BoundaryValueConfig.defaults(), item -> {});

    assertThat(inputs.get(0).input().pathParams())
            .as("happy path key should be the annotation value, not Java param name")
            .containsExactly(java.util.Map.entry("ownerId", "1"));
}

@Test
void requestParamWithExplicitNameUsesAnnotationValueAsKey() {
    // @RequestParam("page") int pageNum — same rename pattern but for query params.
    Parameter renamed = new Parameter(
            "pageNum",
            "Integer",
            java.util.List.of("RequestParam"),
            java.util.Map.of("RequestParam", "page"));
    MethodAnalysis ma = new MethodAnalysis(
            "demo.Ctrl", "list",
            java.util.List.of(renamed),
            io.graphrag.builder.staticanalysis.domain.ReturnType.PRIMITIVE,
            java.util.List.of(),
            java.util.List.of());
    Endpoint ep = new Endpoint("GET:/items", io.graphrag.model.HttpMethod.GET,
            "/items", "demo", "demo.Ctrl", "list", false, java.util.List.of());

    java.util.List<NamedSampleInput> inputs = SampleInputGenerator.generate(
            ep, ma, BoundaryValueConfig.defaults(), item -> {});

    assertThat(inputs.get(0).input().queryParams())
            .containsExactly(java.util.Map.entry("page", "1"));
}
```

The fully-qualified names keep the test addition self-contained without requiring new imports at the top of the file.

- [ ] **Step 2: Run the two new tests to verify they fail**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :graph-rag-builder:test \
  --tests "io.graphrag.builder.staticanalysis.branch.SampleInputGeneratorTest.pathVariableWithExplicitNameUsesAnnotationValueAsKey" \
  --tests "io.graphrag.builder.staticanalysis.branch.SampleInputGeneratorTest.requestParamWithExplicitNameUsesAnnotationValueAsKey"
```

Expected: both FAIL — the current implementation keys by `c.param.name()` (Java name), so `pathParams` will have `"owner"` / `"pageNum"` instead of `"ownerId"` / `"page"`.

- [ ] **Step 3: Commit the failing tests**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/SampleInputGeneratorTest.java
git commit -m "test(staticanalysis): pin SampleInputGenerator annotation-value keying (RED)"
```

---

## Task 5: Implement `SampleInputGenerator.paramKey` helper

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/SampleInputGenerator.java`

- [ ] **Step 1: Add the helper inside the class**

Add this private static method anywhere inside `SampleInputGenerator` (e.g., right before the existing `private record Categorized` line):

```java
private static String paramKey(Parameter p, String annotationName) {
    String v = p.annotationValues().get(annotationName);
    return v != null && !v.isEmpty() ? v : p.name();
}
```

- [ ] **Step 2: Replace the keying in `happy()` and `boundary()`**

In `happy()`, replace the existing switch:

```java
for (Categorized c : params) {
    switch (c.source) {
        case PATH   -> pathParams.put(c.param.name(), BoundaryValueGenerator.happy(c.param.type(), cfg));
        case QUERY  -> queryParams.put(c.param.name(), BoundaryValueGenerator.happy(c.param.type(), cfg));
        case HEADER -> headers.put(c.param.name(), BoundaryValueGenerator.happy(c.param.type(), cfg));
        case BODY   -> body = new LinkedHashMap<>();
        default -> { /* ignored */ }
    }
}
```

with:

```java
for (Categorized c : params) {
    switch (c.source) {
        case PATH   -> pathParams.put(paramKey(c.param, "PathVariable"),
                                      BoundaryValueGenerator.happy(c.param.type(), cfg));
        case QUERY  -> queryParams.put(paramKey(c.param, "RequestParam"),
                                       BoundaryValueGenerator.happy(c.param.type(), cfg));
        case HEADER -> headers.put(paramKey(c.param, "RequestHeader"),
                                   BoundaryValueGenerator.happy(c.param.type(), cfg));
        case BODY   -> body = new LinkedHashMap<>();
        default -> { /* ignored */ }
    }
}
```

In `boundary()`, replace the existing switch:

```java
switch (c.source) {
    case PATH   -> pathParams.put(c.param.name(), value);
    case QUERY  -> queryParams.put(c.param.name(), value);
    case HEADER -> headers.put(c.param.name(), BoundaryValueGenerator.happy(c.param.type(), cfg));
    case BODY   -> body = new LinkedHashMap<>();
    default -> { /* ignored */ }
}
```

with:

```java
switch (c.source) {
    case PATH   -> pathParams.put(paramKey(c.param, "PathVariable"), value);
    case QUERY  -> queryParams.put(paramKey(c.param, "RequestParam"), value);
    case HEADER -> headers.put(paramKey(c.param, "RequestHeader"),
                               BoundaryValueGenerator.happy(c.param.type(), cfg));
    case BODY   -> body = new LinkedHashMap<>();
    default -> { /* ignored */ }
}
```

- [ ] **Step 3: Run the two new tests to verify they pass**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :graph-rag-builder:test \
  --tests "io.graphrag.builder.staticanalysis.branch.SampleInputGeneratorTest.pathVariableWithExplicitNameUsesAnnotationValueAsKey" \
  --tests "io.graphrag.builder.staticanalysis.branch.SampleInputGeneratorTest.requestParamWithExplicitNameUsesAnnotationValueAsKey"
```

Expected: both PASS.

- [ ] **Step 4: Run the full graph-rag-builder suite to confirm no regressions**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :graph-rag-builder:test
```

Expected: 171 GREEN (3 Neo4j-required skipped). The existing tests construct `Parameter` with empty `annotationValues` (via the 3-arg constructor), so `paramKey` falls back to `p.name()` — same behavior as before.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/SampleInputGenerator.java
git commit -m "feat(staticanalysis): SampleInputGenerator prefers annotation values as param keys"
```

---

## Task 6: Failing test for wrapper `APP_BASE_URI` propagation

**Files:**
- Modify: `orchestrator/src/test/java/io/graphrag/orchestrator/PetclinicStage5ScriptTest.java`

- [ ] **Step 1: Update existing happy-path + error-path tests to set `SKIP_SUT_LAUNCH=1`**

The existing tests don't expect the wrapper to launch a real SUT, so they need the bypass env var. In each of the four existing tests (`copiesAllPriorItersAndWritesJacocoXml`, `failsWhenPetclinicDirHasNoPom`, `failsWhenTestsDirMissing`, `cleanupTrapFiresEvenWhenMvnFails`), add `pb.environment().put("SKIP_SUT_LAUNCH", "1");` right after the existing `.put("PETCLINIC_DIR", ...)` line.

For example, the happy-path test changes from:

```java
Map<String, String> env = new HashMap<>();
env.put("PETCLINIC_DIR", petclinic.toString());
env.put("TEST_PACKAGE", "com.example.petclinic.tests");
env.put("PATH", stubBin + ":" + System.getenv("PATH"));
```

to:

```java
Map<String, String> env = new HashMap<>();
env.put("PETCLINIC_DIR", petclinic.toString());
env.put("TEST_PACKAGE", "com.example.petclinic.tests");
env.put("PATH", stubBin + ":" + System.getenv("PATH"));
env.put("SKIP_SUT_LAUNCH", "1");
```

Do the equivalent in the other three tests (using `pb.environment().put(...)` since they use that direct pattern).

- [ ] **Step 2: Append a new test asserting `APP_BASE_URI` propagation**

Add inside the existing class, after the four existing tests:

```java
@Test
void exportsAppBaseUriIntoMvnEnv(@TempDir Path tmp) throws Exception {
    Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
    Path script = projectRoot.resolve("scripts/petclinic-stage5.sh");

    Path stubBin = tmp.resolve("stub-bin");
    Files.createDirectories(stubBin);
    Path sentinel = tmp.resolve("app-base-uri.txt");
    Path mvnStub = stubBin.resolve("mvn");
    Files.writeString(mvnStub, """
            #!/usr/bin/env bash
            set -euo pipefail
            # Record APP_BASE_URI to a sentinel so the test can assert propagation.
            printf '%%s' "${APP_BASE_URI:-<unset>}" > "%s"
            # Honor the wrapper's contract: emit a usable jacoco.xml.
            mkdir -p target/site/jacoco
            cat > target/site/jacoco/jacoco.xml <<'XML'
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <report name="petclinic"></report>
            XML
            """.formatted(sentinel));
    Files.setPosixFilePermissions(mvnStub,
            PosixFilePermissions.fromString("rwxr-xr-x"));

    Path petclinic = tmp.resolve("fake-petclinic");
    Files.createDirectories(petclinic.resolve("src/test/java"));
    Files.writeString(petclinic.resolve("pom.xml"), "<project/>");

    Path iter1Tests = tmp.resolve("out/iter-1/stage4-tests");
    Files.createDirectories(iter1Tests);

    Path jacocoOut = tmp.resolve("out/iter-1/stage5-jacoco.xml");
    Files.createDirectories(jacocoOut.getParent());

    ProcessBuilder pb = new ProcessBuilder(
            "bash", script.toString(),
            iter1Tests.toString(), jacocoOut.toString());
    pb.environment().put("PETCLINIC_DIR", petclinic.toString());
    pb.environment().put("TEST_PACKAGE", "com.example.petclinic.tests");
    pb.environment().put("PATH", stubBin + ":" + System.getenv("PATH"));
    pb.environment().put("SKIP_SUT_LAUNCH", "1");
    pb.environment().put("SUT_PORT", "9999");  // distinct from the default 8084
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String stdout = new String(p.getInputStream().readAllBytes());
    int rc = p.waitFor();
    assertThat(rc).as("stdout:\n%s", stdout).isZero();

    assertThat(Files.readString(sentinel))
            .as("APP_BASE_URI should reflect SUT_PORT=9999")
            .isEqualTo("http://localhost:9999");
}
```

- [ ] **Step 3: Run the new test to verify it fails**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests "io.graphrag.orchestrator.PetclinicStage5ScriptTest.exportsAppBaseUriIntoMvnEnv"
```

Expected: FAIL — the wrapper doesn't yet export `APP_BASE_URI`; the sentinel reads `<unset>`. Also: the existing four tests may fail until Step 1 has been applied — verify by running the whole class.

- [ ] **Step 4: Run the existing four tests to confirm they still pass with the `SKIP_SUT_LAUNCH` env added**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests "io.graphrag.orchestrator.PetclinicStage5ScriptTest" 2>&1 | tail -20
```

Expected: 4 of 5 PASS (the new `exportsAppBaseUriIntoMvnEnv` still RED until Task 7). The four existing tests pass because the wrapper-script change in Task 7 will respect `SKIP_SUT_LAUNCH=1` and skip the SUT launch entirely.

- [ ] **Step 5: Commit the test additions**

```bash
git add orchestrator/src/test/java/io/graphrag/orchestrator/PetclinicStage5ScriptTest.java
git commit -m "test(orchestrator): SKIP_SUT_LAUNCH bypass + APP_BASE_URI propagation (RED)"
```

---

## Task 7: Implement SUT lifecycle + `APP_BASE_URI` in the wrapper

**Files:**
- Modify: `scripts/petclinic-stage5.sh`

- [ ] **Step 1: Add SUT launch / stop functions + env-var declarations**

Insert this block right after the existing `POM_BACKUP="$PETCLINIC_DIR/pom.xml.stage5-bak"` line (before the existing `cleanup()` function):

```bash
SUT_JAR="${SUT_JAR:-$PETCLINIC_DIR/target/spring-petclinic-4.0.0-SNAPSHOT.jar}"
SUT_PORT="${SUT_PORT:-8084}"
SUT_HEALTH_URL="${SUT_HEALTH_URL:-http://localhost:$SUT_PORT/actuator/health}"
SUT_HEALTH_TIMEOUT_SECS="${SUT_HEALTH_TIMEOUT_SECS:-60}"
SUT_PID=""
SUT_LOG="$PETCLINIC_DIR/target/stage5-sut.log"

launch_sut() {
  if [[ "${SKIP_SUT_LAUNCH:-}" == "1" ]]; then
    echo "[stage5] SKIP_SUT_LAUNCH=1 — assuming a SUT is already running on $SUT_PORT (or that the caller doesn't need one)"
    return 0
  fi
  if [[ ! -f "$SUT_JAR" ]]; then
    echo "error: SUT jar not found at $SUT_JAR" >&2
    return 6
  fi
  mkdir -p "$(dirname "$SUT_LOG")"
  : > "$SUT_LOG"
  java -jar "$SUT_JAR" \
    --server.port="$SUT_PORT" \
    --spring.profiles.active=postgres \
    --spring.datasource.url=jdbc:postgresql://localhost:55432/petclinic \
    --spring.datasource.username=appuser \
    --spring.datasource.password=apppass \
    --spring.datasource.driver-class-name=org.postgresql.Driver \
    >> "$SUT_LOG" 2>&1 &
  SUT_PID=$!
  for _ in $(seq 1 "$SUT_HEALTH_TIMEOUT_SECS"); do
    if curl -sf "$SUT_HEALTH_URL" >/dev/null 2>&1; then
      echo "[stage5] SUT healthy at $SUT_HEALTH_URL (pid=$SUT_PID)"
      return 0
    fi
    if ! kill -0 "$SUT_PID" 2>/dev/null; then
      echo "error: SUT process died before becoming healthy; see $SUT_LOG" >&2
      tail -20 "$SUT_LOG" >&2 || true
      SUT_PID=""
      return 7
    fi
    sleep 1
  done
  echo "error: SUT did not become healthy within ${SUT_HEALTH_TIMEOUT_SECS}s" >&2
  tail -20 "$SUT_LOG" >&2 || true
  kill "$SUT_PID" 2>/dev/null || true
  SUT_PID=""
  return 8
}

stop_sut() {
  if [[ -n "$SUT_PID" ]]; then
    kill "$SUT_PID" 2>/dev/null || true
    wait "$SUT_PID" 2>/dev/null || true
    SUT_PID=""
  fi
}
```

- [ ] **Step 2: Extend `cleanup()` to also call `stop_sut`**

Replace the existing `cleanup()` function:

```bash
cleanup() {
  rm -rf "$INJECTED_ROOT"
  if [[ -f "$POM_BACKUP" ]]; then
    mv "$POM_BACKUP" "$PETCLINIC_DIR/pom.xml"
  fi
}
```

with:

```bash
cleanup() {
  stop_sut
  rm -rf "$INJECTED_ROOT"
  if [[ -f "$POM_BACKUP" ]]; then
    mv "$POM_BACKUP" "$PETCLINIC_DIR/pom.xml"
  fi
}
```

- [ ] **Step 3: Call `launch_sut` and export `APP_BASE_URI` into the mvn invocation**

Find the existing block:

```bash
(
  cd "$PETCLINIC_DIR"
  # Skip spring-javaformat's validate goal …
  mvn -q -DskipITs -Dspring-javaformat.skip=true -Dmaven.test.failure.ignore=true test jacoco:report
)
```

and replace it with:

```bash
launch_sut

(
  cd "$PETCLINIC_DIR"
  # Skip spring-javaformat's validate goal — generated tests don't follow
  # Spring's in-tree formatting conventions and that validate would abort
  # the build before reaching the test phase.
  # Ignore test failures so jacoco.xml is still produced.
  # APP_BASE_URI is consumed by TestSynthesizer's @BeforeAll
  #   RestAssured.baseURI = System.getenv("APP_BASE_URI")
  APP_BASE_URI="http://localhost:$SUT_PORT" \
    mvn -q -DskipITs -Dspring-javaformat.skip=true -Dmaven.test.failure.ignore=true \
        test jacoco:report
)
```

- [ ] **Step 4: Run the full wrapper test class to verify all 5 tests pass**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests "io.graphrag.orchestrator.PetclinicStage5ScriptTest"
```

Expected: 5/5 PASS — the four existing tests via `SKIP_SUT_LAUNCH=1` bypass, the new `exportsAppBaseUriIntoMvnEnv` because the wrapper now prepends `APP_BASE_URI=http://localhost:9999 mvn …`.

- [ ] **Step 5: Commit**

```bash
git add scripts/petclinic-stage5.sh
git commit -m "feat(scripts): launch SUT + export APP_BASE_URI so generated tests reach petclinic"
```

---

## Task 8: Verify `gradlew check` and rebuild orchestrator binary

**Files:**
- None (verification + build).

- [ ] **Step 1: Full check**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true check
```

Expected: BUILD SUCCESSFUL across all modules. Especially `:graph-rag-builder:test` (now 173-ish GREEN with the 3 new tests) and `:orchestrator:test` (now 9 GREEN with the new `exportsAppBaseUriIntoMvnEnv`).

- [ ] **Step 2: Rebuild the orchestrator binary**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:installDist
```

Expected: BUILD SUCCESSFUL. Binary at `orchestrator/build/install/orchestrator/bin/orchestrator` updated.

- [ ] **Step 3: No commit** (verification only)

---

## Task 9: Live re-run + collect new reference numbers

**Files:**
- None new; output lands under `/tmp/graph-rag-petclinic-e2e-v2/`.

- [ ] **Step 1: Confirm Postgres is up**

Run:
```bash
docker compose -f samples/scout/petclinic/docker-compose.yml ps
```

Expected: `graphrag-scout-pg` Up (healthy). If not, `docker compose -f samples/scout/petclinic/docker-compose.yml up -d` first.

- [ ] **Step 2: Run the 5-iter acceptance loop**

Run:
```bash
ACCEPT_OUT=/tmp/graph-rag-petclinic-e2e-v2
rm -rf "$ACCEPT_OUT"
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./orchestrator/build/install/orchestrator/bin/orchestrator \
    --sut-source            "$HOME/github_spring-petclinic/spring-petclinic/src/main/java" \
    --project               petclinic \
    --test-package          com.example.petclinic.tests \
    --scout-config-template samples/scout/petclinic/template.yml \
    --scout-base-url        http://localhost:8084 \
    --out                   "$ACCEPT_OUT" \
    --coverage-target       0.70 \
    --max-iterations        5 \
    --scout-launcher-bin    ./scout-launcher/build/install/scout-launcher/bin/scout-launcher \
    --test-generator-bin    ./test-generator/build/install/test-generator/bin/test-generator \
    --user-test-command     ./scripts/petclinic-stage5.sh \
    2>&1 | tee "$ACCEPT_OUT.log"
```

Expected: exit 0. The reduced quarantine count + live SUT should let cumulative coverage grow across iterations. Wall-clock ~10-15 min (each Stage 5 now includes a ~20s SUT startup + mvn test).

- [ ] **Step 3: Collect per-iter numbers**

Run:
```bash
for i in 1 2 3 4 5; do
  d="$ACCEPT_OUT/iter-$i/stage6-feedback"
  [[ -d "$d" ]] || break
  echo "=== iter $i ==="
  python3 -c "
import json
d = json.load(open('$d/coverage-delta.json'))
t = json.load(open('$d/termination-decision.json'))
print(f'branch={d[\"branchCoverage\"]:.3f} line={d[\"lineCoverage\"]:.3f} newly={len(d[\"newlyCovered\"])} missing={len(d[\"stillMissing\"])} terminate={t[\"shouldTerminate\"]} reason={t[\"reason\"]}')
"
done
echo "---"
echo "--- final-report.md ---"
cat "$ACCEPT_OUT/final-report.md"
```

Save the output (paste into Task 10 below).

- [ ] **Step 4: No commit** (run artifacts only)

---

## Task 10: Update the runbook with the new reference numbers

**Files:**
- Modify: `docs/orchestrator-e2e-petclinic.md`

- [ ] **Step 1: Replace the Results table + the limitations §1 paragraph**

Open the runbook. Find the existing `## Results from the reference run (date: 2026-05-30, commit \`e4bebf2\` of petclinic)` table and replace the iter rows with the actual numbers from Task 9 Step 3. Replace the "**Coverage attribution**" paragraph with a note that generated tests now reach the live SUT.

Under `## Known limitations`, edit item #1 to reflect the new state (still note that boundary-value-only generation limits how much coverage can grow per iter — see #2 — but the wiring blockers are gone).

If the new run terminates by hitting `targetReached=true`, update the §"What this run proves" bullet to celebrate the win. If it still terminates via no-progress but at a higher floor, document that too.

- [ ] **Step 2: Commit**

```bash
git add docs/orchestrator-e2e-petclinic.md
git commit -m "docs(runbook): update reference-run numbers after #1 + #3 fixes"
```

---

## Task 11: Push branch updates + add follow-up summary to PR

**Files:**
- None new. Branch push + PR comment.

- [ ] **Step 1: Push**

Run:
```bash
git push
```

Expected: branch `feat/petclinic-e2e` advanced with the new commits.

- [ ] **Step 2: Add a summary comment to PR #13**

Run:
```bash
gh pr comment 13 --body "$(cat <<'EOF'
## Follow-up: runbook §Known limitations #1 + #3 fixed

- (#3) `Parameter.annotationValues` + `DomainAnalyzer` extraction + `SampleInputGenerator.paramKey` — `@PathVariable("ownerId") Owner owner` style paths no longer quarantined.
- (#1) `scripts/petclinic-stage5.sh` now launches petclinic in background, exports `APP_BASE_URI`, stops on EXIT — generated RestAssured tests actually hit the SUT.

New reference run numbers in updated `docs/orchestrator-e2e-petclinic.md`.
EOF
)"
```

Expected: comment posted to the PR.

- [ ] **Step 3: No commit** (comms only).

---

## Self-review notes

Spec coverage:
- §1.1 G1 (wrapper launches SUT + exports APP_BASE_URI + stops on EXIT) → Tasks 6, 7.
- §1.1 G2 (`Parameter` carries `annotationValues`) → Task 1.
- §1.1 G3 (`DomainAnalyzer` populates them) → Tasks 2, 3.
- §1.1 G4 (`SampleInputGenerator` uses them as keys) → Tasks 4, 5.
- §1.1 G5 (live re-run + updated runbook) → Tasks 8, 9, 10.

Placeholder scan: no TBDs; the only conditional content is in Task 10 Step 1 ("if the new run terminates by hitting targetReached=true…") which is the actual runbook-update decision tree, not a missing-detail placeholder.

Type/symbol consistency:
- `Parameter` 4-arg constructor signature `(name, type, annotations, annotationValues)` matches across Task 1 (declaration) → Task 3 (`DomainAnalyzer` call site) → Task 4 (test fixture).
- `paramKey(Parameter, String)` signature consistent in Task 4 (assertion comment) → Task 5 (declaration + call sites).
- `SUT_PORT` env name consistent: declared in Task 7 wrapper, set in Task 6 new test, asserted in same.
- `SKIP_SUT_LAUNCH=1` flag consistent: set in Task 6's test updates, checked in Task 7's `launch_sut`.
