# graph-rag-builder T3 + CLI + Orchestrator Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the three deliverables left from the T1+T2 session: (1) `staticanalysis/branch/` package — deterministic happy-path + boundary-value `ExploredPath` generation and the `StaticAnalysisPathExplorer` SPI impl; (2) `staticanalysis/cli/` package — a standalone CLI that emits `endpoints.json` + `paths.json` + `static-analysis-report.json`; (3) orchestrator `IterationRunner` switch from `PathDiscoveryStatic.discover` to the in-process `AstParser → DomainAnalyzer → BranchAnalyzer` pipeline, followed by deletion of the `:path-discovery-static` module.

**Architecture:** New code lives entirely inside the existing `graph-rag-builder` module under `io.graphrag.builder.staticanalysis.{branch,cli}`. `BranchAnalyzer` consumes the existing `DomainAnalysisResult` from T2, generates one happy `NamedSampleInput` per endpoint plus boundary variants per numeric/string path/query parameter, and wraps each into an `ExploredPath` with deterministic slug ids matching the existing `path-discovery-static` convention. The CLI is a thin wrapper around `AstParser → DomainAnalyzer → BranchAnalyzer` + JSON output. The orchestrator integrates in-process (no subprocess), then `:path-discovery-static` is deleted in a separate commit.

**Tech Stack:** Java 17, JavaParser symbol-solver 3.26.2 (already on classpath from T1+T2), JUnit 5.10.2, AssertJ 3.26.3, Jackson 2.18.2, Gradle 8.13 (`-Pagent.enabled=true` build flag required).

**Working directory:** `/Users/changjoonbaek/graph-rag/graph-rag` (branch `feat/t6-orchestrator`).

**Build command shorthand:** `gw` below means
```
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true
```
Set this once per shell:
```bash
export JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home
alias gw='./gradlew -Pagent.enabled=true'
```

**Spec:** `docs/superpowers/specs/2026-05-29-graph-rag-builder-t3-cli-switch-design.md`

---

## File Structure (locked in before tasks)

```
graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/
├── package-info.java
├── BoundaryValueConfig.java               (record)
├── BoundaryValueGenerator.java
├── ManualReviewItem.java                  (record)
├── ManualReviewSink.java                  (functional interface + factories)
├── NamedSampleInput.java                  (record, package-private)
├── SampleInputGenerator.java
├── ExploredPathBuilder.java
├── BranchAnalysisResult.java              (record)
├── BranchAnalyzer.java
└── StaticAnalysisPathExplorer.java

graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/
├── package-info.java
├── StaticAnalysisOptions.java             (record)
├── StaticAnalysisOptionsParser.java
├── StaticAnalysisReport.java              (record + nested records)
└── StaticAnalysisCli.java                 (main + run)

graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/
├── BoundaryValueGeneratorTest.java
├── SampleInputGeneratorTest.java
├── ExploredPathBuilderTest.java
├── BranchAnalyzerTest.java
└── StaticAnalysisPathExplorerTest.java

graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/cli/
├── StaticAnalysisOptionsParserTest.java
└── StaticAnalysisCliTest.java

orchestrator/build.gradle.kts                       (modify)
orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java (modify)
settings.gradle.kts                                 (modify in deletion task)
path-discovery-static/                              (deleted in deletion task)
```

Reuses fixture from T1+T2 session:
`graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/org/example/petclinic/{Owner,OwnerService,OwnerRepository,OwnerRestController}.java`.

---

## Task 1: Scaffold `branch/` package + helper records

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/package-info.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/ManualReviewItem.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/ManualReviewSink.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/NamedSampleInput.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/BranchAnalysisResult.java`

These are pure data carriers + a functional interface. Behavior is exercised by the tests in later tasks.

- [ ] **Step 1: Create `package-info.java`**

```java
/**
 * Stage 3 of the static analyzer: deterministic boundary-value {@code SampleInput}
 * generation per endpoint plus the {@code PathExplorer} SPI implementation.
 *
 * <p>{@link io.graphrag.builder.staticanalysis.branch.BranchAnalyzer} consumes the
 * {@link io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult} from Stage 2
 * and produces a {@link io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult}
 * containing one happy {@code ExploredPath} per endpoint plus one variant per
 * numeric / string path/query parameter. Output is deterministic — slug ids match
 * {@code static_{handlerMethod}_{variant}} per the work-order convention.
 *
 * <p>{@link io.graphrag.builder.staticanalysis.branch.StaticAnalysisPathExplorer}
 * exposes the same generation surface through the
 * {@link io.graphrag.builder.exploration.PathExplorer} SPI.
 */
package io.graphrag.builder.staticanalysis.branch;
```

- [ ] **Step 2: Create `ManualReviewItem.java`**

```java
package io.graphrag.builder.staticanalysis.branch;

import java.util.Objects;

/**
 * Static analyzer surfaced something it cannot generate a deterministic input for
 * (missing method analysis, complex parameter type, etc). Surfaced via
 * {@link BranchAnalysisResult#manualReviewQueue()} for downstream tooling to log
 * or escalate.
 */
public record ManualReviewItem(String kind, String reason, String location) {

    public ManualReviewItem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(location, "location");
    }
}
```

- [ ] **Step 3: Create `ManualReviewSink.java`**

```java
package io.graphrag.builder.staticanalysis.branch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Consumer of {@link ManualReviewItem}s emitted by branch analysis. Exists as a
 * named functional interface (instead of a raw {@code Consumer<ManualReviewItem>})
 * so that {@link StaticAnalysisPathExplorer} can opt into discarding without
 * surfacing it in the SPI contract.
 */
@FunctionalInterface
public interface ManualReviewSink {

    void accept(ManualReviewItem item);

    /** A sink that collects items into the supplied list (caller-owned). */
    static ManualReviewSink collectingInto(List<ManualReviewItem> bucket) {
        return bucket::add;
    }

    /** A sink that swallows every item — used by the PathExplorer SPI. */
    static ManualReviewSink discarding() {
        return item -> { };
    }

    /**
     * Convenience helper: returns a fresh sink + the backing list as a pair so the
     * caller can grab the items at the end. Returned list is mutable until
     * {@link CollectingSink#frozen()} is called.
     */
    static CollectingSink collecting() {
        return new CollectingSink();
    }

    /** Mutable accumulator returned by {@link #collecting()}. */
    final class CollectingSink implements ManualReviewSink {
        private final List<ManualReviewItem> items = new ArrayList<>();
        @Override public void accept(ManualReviewItem item) { items.add(item); }
        public List<ManualReviewItem> frozen() {
            return Collections.unmodifiableList(new ArrayList<>(items));
        }
    }
}
```

- [ ] **Step 4: Create `NamedSampleInput.java`**

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.model.SampleInput;

import java.util.Objects;

/**
 * Internal carrier emitted by {@link SampleInputGenerator}. Pairs a {@link SampleInput}
 * with the slug + predicted HTTP status that {@link ExploredPathBuilder} needs to
 * compose an {@code ExploredPath}.
 *
 * @param slug             variant tag (e.g. {@code "happy"}, {@code "id-neg1"})
 * @param predictedStatus  expected HTTP response status code
 * @param input            populated {@link SampleInput}
 */
record NamedSampleInput(String slug, int predictedStatus, SampleInput input) {

    NamedSampleInput {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(input, "input");
    }
}
```

- [ ] **Step 5: Create `BranchAnalysisResult.java`**

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.model.ExploredPath;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link BranchAnalyzer#analyze}. Both lists are unmodifiable and
 * deterministic — same input → equal result records.
 */
public record BranchAnalysisResult(
        List<ExploredPath> paths,
        List<ManualReviewItem> manualReviewQueue) {

    public BranchAnalysisResult {
        paths              = List.copyOf(Objects.requireNonNull(paths,              "paths"));
        manualReviewQueue  = List.copyOf(Objects.requireNonNull(manualReviewQueue,  "manualReviewQueue"));
    }
}
```

- [ ] **Step 6: Verify compile**

Run:
```bash
gw :graph-rag-builder:compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/
git commit -m "$(cat <<'EOF'
chore(staticanalysis): scaffold branch/ package with helper records

Adds package-info plus the four data carriers BranchAnalyzer will need —
ManualReviewItem, ManualReviewSink, NamedSampleInput, BranchAnalysisResult.
No behavior yet; tests follow once the generators land.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `BoundaryValueConfig` record

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/BoundaryValueConfig.java`

- [ ] **Step 1: Create `BoundaryValueConfig.java`**

```java
package io.graphrag.builder.staticanalysis.branch;

import java.util.List;
import java.util.Objects;

/**
 * Per-type boundary value rules consumed by {@link BoundaryValueGenerator}.
 *
 * <p>v1 ships only static defaults via {@link #defaults()}; the parameterized
 * constructor exists so that a future session can override per-project (e.g.
 * to add Long.MIN_VALUE, double NaN, etc.) without changing the generator API.
 *
 * @param numericVariants  numeric boundary values excluding {@link #numericHappy}.
 *                         Used in order; v1: {@code ["-1", "0", "2147483647"]}.
 * @param numericHappy     happy-path numeric value (v1: {@code "1"}).
 * @param stringVariants   string boundary values excluding {@link #stringHappy}.
 *                         v1: {@code [""]}.
 * @param stringHappy      happy-path string value (v1: {@code "a"}).
 */
public record BoundaryValueConfig(
        List<String> numericVariants,
        String numericHappy,
        List<String> stringVariants,
        String stringHappy) {

    public BoundaryValueConfig {
        numericVariants = List.copyOf(Objects.requireNonNull(numericVariants, "numericVariants"));
        Objects.requireNonNull(numericHappy, "numericHappy");
        stringVariants  = List.copyOf(Objects.requireNonNull(stringVariants,  "stringVariants"));
        Objects.requireNonNull(stringHappy, "stringHappy");
    }

    public static BoundaryValueConfig defaults() {
        return new BoundaryValueConfig(
                List.of("-1", "0", String.valueOf(Integer.MAX_VALUE)),
                "1",
                List.of(""),
                "a");
    }
}
```

- [ ] **Step 2: Verify compile**

Run:
```bash
gw :graph-rag-builder:compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/BoundaryValueConfig.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): BoundaryValueConfig — per-type variant rules

v1 defaults: numeric ["-1", "0", MAX_INT], happy "1"; string [""], happy "a".
Constructor is public so a future session can override without API churn.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `BoundaryValueGenerator` + tests

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/BoundaryValueGenerator.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/BoundaryValueGeneratorTest.java`

- [ ] **Step 1: Write the failing test**

`graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/BoundaryValueGeneratorTest.java`:

```java
package io.graphrag.builder.staticanalysis.branch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoundaryValueGeneratorTest {

    private final BoundaryValueConfig cfg = BoundaryValueConfig.defaults();

    @Test
    void numeric_int_happy_is_one() {
        assertThat(BoundaryValueGenerator.happy("int", cfg)).isEqualTo("1");
        assertThat(BoundaryValueGenerator.happy("Integer", cfg)).isEqualTo("1");
    }

    @Test
    void numeric_int_variants_are_neg1_zero_maxint() {
        assertThat(BoundaryValueGenerator.variants("Integer", cfg))
                .containsExactly("-1", "0", String.valueOf(Integer.MAX_VALUE));
    }

    @Test
    void string_happy_is_a() {
        assertThat(BoundaryValueGenerator.happy("String", cfg)).isEqualTo("a");
    }

    @Test
    void string_variants_contain_empty() {
        assertThat(BoundaryValueGenerator.variants("String", cfg)).containsExactly("");
    }

    @Test
    void isNumeric_recognises_primitive_and_boxed() {
        List.of("int", "Integer", "long", "Long", "short", "Short",
                "byte", "Byte", "double", "Double", "float", "Float")
            .forEach(t -> assertThat(BoundaryValueGenerator.isNumeric(t))
                    .as("isNumeric(\"%s\")", t).isTrue());
    }

    @Test
    void isStringLike_recognises_String_and_CharSequence() {
        assertThat(BoundaryValueGenerator.isStringLike("String")).isTrue();
        assertThat(BoundaryValueGenerator.isStringLike("CharSequence")).isTrue();
        assertThat(BoundaryValueGenerator.isStringLike("Integer")).isFalse();
    }

    @Test
    void complex_type_yields_empty_variants_and_empty_happy() {
        assertThat(BoundaryValueGenerator.variants("OwnerDto", cfg)).isEmpty();
        assertThat(BoundaryValueGenerator.happy("OwnerDto", cfg)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.BoundaryValueGeneratorTest"
```
Expected: compilation failure — `BoundaryValueGenerator` does not exist.

- [ ] **Step 3: Implement `BoundaryValueGenerator`**

```java
package io.graphrag.builder.staticanalysis.branch;

import java.util.List;
import java.util.Set;

/**
 * Pure static helpers that turn a parameter type name into the happy + variant
 * values declared in {@link BoundaryValueConfig}. Anything that isn't numeric or
 * string-like falls through to empty strings — the caller treats this as
 * "no boundary variant for this param" and may emit a {@link ManualReviewItem}.
 */
public final class BoundaryValueGenerator {

    private static final Set<String> NUMERIC = Set.of(
            "int", "Integer",
            "long", "Long",
            "short", "Short",
            "byte", "Byte",
            "double", "Double",
            "float", "Float");

    private static final Set<String> STRING_LIKE = Set.of(
            "String", "CharSequence");

    private BoundaryValueGenerator() {}

    public static boolean isNumeric(String typeName) {
        return typeName != null && NUMERIC.contains(typeName);
    }

    public static boolean isStringLike(String typeName) {
        return typeName != null && STRING_LIKE.contains(typeName);
    }

    public static String happy(String typeName, BoundaryValueConfig cfg) {
        if (isNumeric(typeName))    return cfg.numericHappy();
        if (isStringLike(typeName)) return cfg.stringHappy();
        return "";
    }

    public static List<String> variants(String typeName, BoundaryValueConfig cfg) {
        if (isNumeric(typeName))    return cfg.numericVariants();
        if (isStringLike(typeName)) return cfg.stringVariants();
        return List.of();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.BoundaryValueGeneratorTest"
```
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/BoundaryValueGenerator.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/BoundaryValueGeneratorTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): BoundaryValueGenerator — per-type happy + variants

Pure static helpers consumed by SampleInputGenerator. Numeric (primitive +
boxed for int/long/short/byte/double/float) and string-like (String,
CharSequence) types yield their cfg-defined values; complex types fall
through to empty strings so the caller skips that boundary.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `SampleInputGenerator` + tests

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/SampleInputGenerator.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/SampleInputGeneratorTest.java`

- [ ] **Step 1: Write the failing test**

`graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/SampleInputGeneratorTest.java`:

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.builder.staticanalysis.domain.Parameter;
import io.graphrag.builder.staticanalysis.domain.ReturnType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SampleInputGeneratorTest {

    private final BoundaryValueConfig cfg = BoundaryValueConfig.defaults();
    private final List<ManualReviewItem> queue = new ArrayList<>();
    private final ManualReviewSink sink = ManualReviewSink.collectingInto(queue);

    private static Endpoint ep(HttpMethod m, String path, String klass, String mname) {
        return new Endpoint(m.name() + ":" + path, m, path, "petclinic",
                klass, mname, false, List.of());
    }

    private static MethodAnalysis ma(String klass, String mname, List<Parameter> params) {
        return new MethodAnalysis(klass, mname, params,
                List.of(), List.of(), ReturnType.of("void"));
    }

    @Test
    void endpoint_with_no_params_emits_only_happy() {
        Endpoint endpoint = ep(HttpMethod.GET, "/vets", "com.x.VetController", "list");
        MethodAnalysis methodAnalysis = ma("com.x.VetController", "list", List.of());

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).slug()).isEqualTo("happy");
        assertThat(inputs.get(0).predictedStatus()).isEqualTo(200);
        assertThat(inputs.get(0).input().pathParams()).isEmpty();
        assertThat(inputs.get(0).input().queryParams()).isEmpty();
        assertThat(inputs.get(0).input().body()).isNull();
        assertThat(queue).isEmpty();
    }

    @Test
    void single_numeric_pathvar_emits_happy_plus_three_variants() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "get", List.of(id));

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        assertThat(inputs).hasSize(4);
        assertThat(inputs.get(0).slug()).isEqualTo("happy");
        assertThat(inputs.get(0).input().pathParams()).containsExactly(Map.entry("id", "1"));
        assertThat(inputs.get(0).predictedStatus()).isEqualTo(200);

        assertThat(inputs.get(1).slug()).isEqualTo("id-neg1");
        assertThat(inputs.get(1).input().pathParams()).containsExactly(Map.entry("id", "-1"));
        assertThat(inputs.get(1).predictedStatus()).isEqualTo(404);

        assertThat(inputs.get(2).slug()).isEqualTo("id-0");
        assertThat(inputs.get(2).input().pathParams()).containsExactly(Map.entry("id", "0"));

        assertThat(inputs.get(3).slug()).isEqualTo("id-" + Integer.MAX_VALUE);
        assertThat(inputs.get(3).input().pathParams())
                .containsExactly(Map.entry("id", String.valueOf(Integer.MAX_VALUE)));
    }

    @Test
    void single_string_querystring_emits_happy_plus_empty() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners", "com.x.OwnerController", "search");
        Parameter q = new Parameter("q", "String", List.of("RequestParam"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "search", List.of(q));

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0).input().queryParams()).containsExactly(Map.entry("q", "a"));
        assertThat(inputs.get(1).slug()).isEqualTo("q-empty");
        assertThat(inputs.get(1).input().queryParams()).containsExactly(Map.entry("q", ""));
        assertThat(inputs.get(1).predictedStatus()).isEqualTo(400);
    }

    @Test
    void request_body_endpoint_gets_empty_object_body() {
        Endpoint endpoint = ep(HttpMethod.POST, "/owners", "com.x.OwnerController", "create");
        Parameter body = new Parameter("body", "Owner", List.of("RequestBody"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "create", List.of(body));

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        assertThat(inputs).hasSize(1);
        SampleInput happy = inputs.get(0).input();
        assertThat(happy.body()).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) happy.body()).isEmpty();
        assertThat(inputs.get(0).predictedStatus()).isEqualTo(201);
        // Body params get a complex_parameter_type queue entry because Owner is not numeric/string.
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).kind()).isEqualTo("complex_parameter_type");
    }

    @Test
    void multi_param_only_varies_one_at_a_time() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}/pets/{petId}",
                "com.x.OwnerController", "getPet");
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        Parameter petId = new Parameter("petId", "Integer", List.of("PathVariable"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "getPet", List.of(id, petId));

        List<NamedSampleInput> inputs =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, sink);

        // 1 happy + 3 variants for id + 3 variants for petId = 7
        assertThat(inputs).hasSize(7);
        assertThat(inputs.get(0).slug()).isEqualTo("happy");

        // When varying id, petId stays "1".
        NamedSampleInput idVariant = inputs.get(1);
        assertThat(idVariant.slug()).isEqualTo("id-neg1");
        assertThat(idVariant.input().pathParams())
                .containsExactly(Map.entry("id", "-1"), Map.entry("petId", "1"));

        // When varying petId, id stays "1".
        NamedSampleInput petIdVariant = inputs.get(4);
        assertThat(petIdVariant.slug()).isEqualTo("petId-neg1");
        assertThat(petIdVariant.input().pathParams())
                .containsExactly(Map.entry("id", "1"), Map.entry("petId", "-1"));
    }

    @Test
    void deterministic_order_under_repeat_invocation() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        MethodAnalysis methodAnalysis = ma("com.x.OwnerController", "get", List.of(id));

        List<NamedSampleInput> r1 =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, ManualReviewSink.discarding());
        List<NamedSampleInput> r2 =
                SampleInputGenerator.generate(endpoint, methodAnalysis, cfg, ManualReviewSink.discarding());

        assertThat(r1).isEqualTo(r2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.SampleInputGeneratorTest"
```
Expected: compilation failure — `SampleInputGenerator` does not exist.

- [ ] **Step 3: Implement `SampleInputGenerator`**

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.builder.staticanalysis.domain.Parameter;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the happy + boundary {@link NamedSampleInput}s for one endpoint.
 *
 * <p>Algorithm (deterministic):
 * <ol>
 *   <li>Categorize parameters by annotation simple-name into pathParams /
 *       queryParams / headers / body / ignored.</li>
 *   <li>Emit the happy input first.</li>
 *   <li>For each path/query parameter in declaration order, emit one variant per
 *       {@link BoundaryValueGenerator#variants}. Only the varying parameter
 *       changes; the others keep their happy values.</li>
 * </ol>
 *
 * <p>Header parameters and body fields do not produce variants in v1 (see spec §3.3).
 */
public final class SampleInputGenerator {

    /** Spring-binding sentinel parameter types we silently drop. */
    private static final Set<String> IGNORED_TYPES = Set.of(
            "Model", "ModelMap", "BindingResult",
            "HttpServletRequest", "HttpServletResponse",
            "HttpSession", "Authentication", "Principal");

    private enum Source { PATH, QUERY, HEADER, BODY, IGNORED }

    private SampleInputGenerator() {}

    public static List<NamedSampleInput> generate(
            Endpoint endpoint,
            MethodAnalysis methodAnalysis,
            BoundaryValueConfig cfg,
            ManualReviewSink sink) {

        List<Categorized> params = categorize(methodAnalysis.parameters(),
                endpoint.handlerClass(), endpoint.handlerMethod(), sink);

        List<NamedSampleInput> out = new ArrayList<>();
        out.add(happy(endpoint, params, cfg));

        for (Categorized p : params) {
            if (p.source != Source.PATH && p.source != Source.QUERY) continue;
            for (String variant : BoundaryValueGenerator.variants(p.param.type(), cfg)) {
                out.add(boundary(endpoint, params, p, variant, cfg));
            }
        }
        return List.copyOf(out);
    }

    private static List<Categorized> categorize(
            List<Parameter> parameters, String classFqn, String methodName,
            ManualReviewSink sink) {

        List<Categorized> out = new ArrayList<>(parameters.size());
        for (Parameter p : parameters) {
            Source src = sourceOf(p);
            if (src == Source.IGNORED) continue;
            if (src == Source.BODY) {
                // Body objects are not generated field-by-field in v1.
                sink.accept(new ManualReviewItem(
                        "complex_parameter_type",
                        "request body fields are not boundary-generated in v1",
                        classFqn + "#" + methodName + "(" + p.type() + " " + p.name() + ")"));
            } else if (!BoundaryValueGenerator.isNumeric(p.type())
                    && !BoundaryValueGenerator.isStringLike(p.type())) {
                sink.accept(new ManualReviewItem(
                        "complex_parameter_type",
                        "no boundary generator for type",
                        classFqn + "#" + methodName + "(" + p.type() + " " + p.name() + ")"));
            }
            out.add(new Categorized(p, src));
        }
        return out;
    }

    private static Source sourceOf(Parameter p) {
        if (p.annotations().contains("PathVariable"))   return Source.PATH;
        if (p.annotations().contains("RequestParam"))   return Source.QUERY;
        if (p.annotations().contains("RequestHeader"))  return Source.HEADER;
        if (p.annotations().contains("RequestBody"))    return Source.BODY;
        if (IGNORED_TYPES.contains(p.type()))           return Source.IGNORED;
        // Unannotated, non-sentinel → treat as query param (Spring default binding).
        return Source.QUERY;
    }

    private static NamedSampleInput happy(Endpoint endpoint, List<Categorized> params,
                                          BoundaryValueConfig cfg) {
        Map<String, String> pathParams = new LinkedHashMap<>();
        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Object body = null;
        for (Categorized c : params) {
            switch (c.source) {
                case PATH   -> pathParams.put(c.param.name(), BoundaryValueGenerator.happy(c.param.type(), cfg));
                case QUERY  -> queryParams.put(c.param.name(), BoundaryValueGenerator.happy(c.param.type(), cfg));
                case HEADER -> headers.put(c.param.name(), BoundaryValueGenerator.happy(c.param.type(), cfg));
                case BODY   -> body = new LinkedHashMap<>();
                default -> { /* ignored */ }
            }
        }
        return new NamedSampleInput("happy", happyStatus(endpoint.method()),
                new SampleInput(headers, pathParams, queryParams, body));
    }

    private static NamedSampleInput boundary(Endpoint endpoint, List<Categorized> params,
                                             Categorized mutated, String variantValue,
                                             BoundaryValueConfig cfg) {
        Map<String, String> pathParams = new LinkedHashMap<>();
        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Object body = null;
        for (Categorized c : params) {
            String value = (c == mutated)
                    ? variantValue
                    : BoundaryValueGenerator.happy(c.param.type(), cfg);
            switch (c.source) {
                case PATH   -> pathParams.put(c.param.name(), value);
                case QUERY  -> queryParams.put(c.param.name(), value);
                case HEADER -> headers.put(c.param.name(), BoundaryValueGenerator.happy(c.param.type(), cfg));
                case BODY   -> body = new LinkedHashMap<>();
                default -> { /* ignored */ }
            }
        }
        String slug = mutated.param.name() + "-" + slugifyValue(variantValue);
        int status = "".equals(variantValue) ? 400 : 404;
        return new NamedSampleInput(slug, status,
                new SampleInput(headers, pathParams, queryParams, body));
    }

    private static String slugifyValue(String v) {
        if (v.isEmpty()) return "empty";
        if (v.startsWith("-")) return "neg" + v.substring(1);
        return v;
    }

    private static int happyStatus(HttpMethod m) {
        return m == HttpMethod.POST ? 201 : 200;
    }

    private record Categorized(Parameter param, Source source) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.SampleInputGeneratorTest"
```
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/SampleInputGenerator.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/SampleInputGeneratorTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): SampleInputGenerator — happy + per-param boundary inputs

Categorises parameters by annotation simple-name (PathVariable / RequestParam /
RequestHeader / RequestBody / sentinel-type ignore), emits one happy
NamedSampleInput, then one variant per (path|query) × boundary value. Body
fields produce a complex_parameter_type review entry; multi-param endpoints
vary exactly one parameter at a time so the call site can correlate slug to
parameter directly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `ExploredPathBuilder` + tests

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/ExploredPathBuilder.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/ExploredPathBuilderTest.java`

- [ ] **Step 1: Write the failing test**

`graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/ExploredPathBuilderTest.java`:

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExploredPathBuilderTest {

    private static Endpoint ep(HttpMethod m, String path, String klass, String mname) {
        return new Endpoint(m.name() + ":" + path, m, path, "petclinic",
                klass, mname, false, List.of());
    }

    private static NamedSampleInput happy(int status) {
        return new NamedSampleInput("happy", status,
                new SampleInput(Map.of(), Map.of(), Map.of(), null));
    }

    private static NamedSampleInput boundary(String slug, int status) {
        return new NamedSampleInput(slug, status,
                new SampleInput(Map.of(), Map.of("id", "-1"), Map.of(), null));
    }

    @Test
    void slug_uses_handler_method_name() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        List<ExploredPath> out = ExploredPathBuilder.build(
                endpoint, List.of(happy(200), boundary("id-neg1", 404)), "v1");

        assertThat(out.get(0).id()).isEqualTo("static_get_happy");
        assertThat(out.get(1).id()).isEqualTo("static_get_id-neg1");
    }

    @Test
    void happy_uses_200_for_GET() {
        Endpoint endpoint = ep(HttpMethod.GET, "/vets", "com.x.VetController", "list");
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(happy(200)), "v1").get(0);
        assertThat(p.exitStatus()).isEqualTo(200);
    }

    @Test
    void happy_uses_201_for_POST() {
        Endpoint endpoint = ep(HttpMethod.POST, "/owners", "com.x.OwnerController", "create");
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(happy(201)), "v1").get(0);
        assertThat(p.exitStatus()).isEqualTo(201);
    }

    @Test
    void numeric_boundary_predicts_404() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        ExploredPath p = ExploredPathBuilder.build(
                endpoint, List.of(happy(200), boundary("id-neg1", 404)), "v1").get(1);
        assertThat(p.exitStatus()).isEqualTo(404);
    }

    @Test
    void empty_string_boundary_predicts_400() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners", "com.x.OwnerController", "search");
        NamedSampleInput empty = new NamedSampleInput("q-empty", 400,
                new SampleInput(Map.of(), Map.of(), Map.of("q", ""), null));
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(empty), "v1").get(0);
        assertThat(p.exitStatus()).isEqualTo(400);
    }

    @Test
    void discoveredBy_is_MANUAL() {
        Endpoint endpoint = ep(HttpMethod.GET, "/vets", "com.x.VetController", "list");
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(happy(200)), "v1").get(0);
        assertThat(p.discoveredBy()).isEqualTo(PathExplorerKind.MANUAL);
    }

    @Test
    void coverage_signature_matches_convention() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        List<ExploredPath> out = ExploredPathBuilder.build(
                endpoint, List.of(happy(200), boundary("id-neg1", 404)), "v1");
        assertThat(out.get(0).coverageSignature()).isEqualTo("static:GET:/owners/{id}:happy");
        assertThat(out.get(1).coverageSignature()).isEqualTo("static:GET:/owners/{id}:id-neg1");
    }

    @Test
    void branches_taken_uses_handler_class_dot_method_colon_slug() {
        Endpoint endpoint = ep(HttpMethod.GET, "/owners/{id}", "com.x.OwnerController", "get");
        ExploredPath p = ExploredPathBuilder.build(endpoint, List.of(happy(200)), "v1").get(0);
        assertThat(p.branchesTaken())
                .containsExactly("com.x.OwnerController.get:happy");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.ExploredPathBuilderTest"
```
Expected: compilation failure — `ExploredPathBuilder` does not exist.

- [ ] **Step 3: Implement `ExploredPathBuilder`**

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.PathExplorerKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps {@link NamedSampleInput}s into shared-model {@link ExploredPath}s.
 *
 * <p>Slug + signature convention matches the legacy {@code path-discovery-static}
 * {@code ExploredPathBuilder} so coverage feedback stays backward compatible:
 * <ul>
 *   <li>{@code id = "static_" + handlerMethod + "_" + slug}</li>
 *   <li>{@code coverageSignature = "static:" + endpoint.id() + ":" + slug}</li>
 *   <li>{@code branchesTaken = [handlerClass + "." + handlerMethod + ":" + slug]}</li>
 *   <li>{@code discoveredBy = PathExplorerKind.MANUAL} (no shared-model change)</li>
 * </ul>
 */
public final class ExploredPathBuilder {

    private ExploredPathBuilder() {}

    public static List<ExploredPath> build(Endpoint endpoint,
                                           List<NamedSampleInput> inputs,
                                           String codeVersion) {
        List<ExploredPath> out = new ArrayList<>(inputs.size());
        for (NamedSampleInput in : inputs) {
            out.add(new ExploredPath(
                    "static_" + endpoint.handlerMethod() + "_" + in.slug(),
                    endpoint.id(),
                    PathExplorerKind.MANUAL,
                    in.input(),
                    /* pathConstraint */ null,
                    List.of(endpoint.handlerClass() + "." + endpoint.handlerMethod() + ":" + in.slug()),
                    in.predictedStatus(),
                    /* exitResponseShape */ null,
                    "static:" + endpoint.id() + ":" + in.slug(),
                    codeVersion));
        }
        return List.copyOf(out);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.ExploredPathBuilderTest"
```
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/ExploredPathBuilder.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/ExploredPathBuilderTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): ExploredPathBuilder — slug + signature wrapper

Wraps the NamedSampleInputs from SampleInputGenerator into shared-model
ExploredPaths using the legacy path-discovery-static slug/coverage-signature
convention so Stage 6 coverage feedback stays backward compatible.
discoveredBy is PathExplorerKind.MANUAL per work-order §11.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `BranchAnalyzer` + tests

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/BranchAnalyzer.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/BranchAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

`graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/BranchAnalyzerTest.java`:

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.staticanalysis.domain.CallGraph;
import io.graphrag.builder.staticanalysis.domain.ClassRole;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.builder.staticanalysis.domain.Parameter;
import io.graphrag.builder.staticanalysis.domain.ReturnType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BranchAnalyzerTest {

    private final BoundaryValueConfig cfg = BoundaryValueConfig.defaults();

    private static Endpoint ep(String klass, String mname, String path, HttpMethod method) {
        return new Endpoint(method.name() + ":" + path, method, path, "petclinic",
                klass, mname, false, List.of());
    }

    private static MethodAnalysis ma(String klass, String mname, List<Parameter> params) {
        return new MethodAnalysis(klass, mname, params,
                List.of(), List.of(), ReturnType.of("void"));
    }

    @Test
    void endpoint_missing_method_analysis_yields_only_happy_and_logs_queue_entry() {
        Endpoint endpoint = ep("com.x.UnknownCtl", "missing", "/foo", HttpMethod.GET);
        DomainAnalysisResult domain = new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(), CallGraph.empty());

        BranchAnalysisResult result = BranchAnalyzer.analyze(
                domain, "v1", 10, cfg, Set.of());

        assertThat(result.paths()).hasSize(1);
        assertThat(result.paths().get(0).id()).isEqualTo("static_missing_happy");
        assertThat(result.paths().get(0).sampleInput().pathParams()).isEmpty();
        assertThat(result.manualReviewQueue()).hasSize(1);
        assertThat(result.manualReviewQueue().get(0).kind()).isEqualTo("missing_method_analysis");
    }

    @Test
    void excludePaths_skips_endpoint_entirely() {
        Endpoint kept = ep("com.x.A", "list", "/a", HttpMethod.GET);
        Endpoint skipped = ep("com.x.B", "list", "/b", HttpMethod.GET);
        Map<String, MethodAnalysis> mas = new LinkedHashMap<>();
        mas.put(kept.handlerClass() + "#" + kept.handlerMethod(), ma(kept.handlerClass(), kept.handlerMethod(), List.of()));
        mas.put(skipped.handlerClass() + "#" + skipped.handlerMethod(), ma(skipped.handlerClass(), skipped.handlerMethod(), List.of()));
        DomainAnalysisResult domain = new DomainAnalysisResult(
                List.of(kept, skipped),
                Map.of(kept.handlerClass(), ClassRole.CONTROLLER,
                       skipped.handlerClass(), ClassRole.CONTROLLER),
                mas, CallGraph.empty());

        BranchAnalysisResult result = BranchAnalyzer.analyze(
                domain, "v1", 10, cfg, Set.of(skipped.id()));

        assertThat(result.paths()).extracting(ExploredPath::endpointId)
                .containsExactly(kept.id());
        assertThat(result.manualReviewQueue()).isEmpty();
    }

    @Test
    void maxPerEndpoint_cap_keeps_happy_first() {
        Endpoint endpoint = ep("com.x.OwnerCtl", "get", "/owners/{id}", HttpMethod.GET);
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        DomainAnalysisResult domain = new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(endpoint.handlerClass() + "#" + endpoint.handlerMethod(),
                        ma(endpoint.handlerClass(), endpoint.handlerMethod(), List.of(id))),
                CallGraph.empty());

        BranchAnalysisResult result = BranchAnalyzer.analyze(domain, "v1",
                /* maxPathsPerEndpoint */ 2, cfg, Set.of());

        assertThat(result.paths()).hasSize(2);
        assertThat(result.paths().get(0).id()).isEqualTo("static_get_happy");
        assertThat(result.paths().get(1).id()).isEqualTo("static_get_id-neg1");
    }

    @Test
    void idempotent_under_repeat_invocation() {
        Endpoint endpoint = ep("com.x.OwnerCtl", "get", "/owners/{id}", HttpMethod.GET);
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        DomainAnalysisResult domain = new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(endpoint.handlerClass() + "#" + endpoint.handlerMethod(),
                        ma(endpoint.handlerClass(), endpoint.handlerMethod(), List.of(id))),
                CallGraph.empty());

        BranchAnalysisResult r1 = BranchAnalyzer.analyze(domain, "v1", 10, cfg, Set.of());
        BranchAnalysisResult r2 = BranchAnalyzer.analyze(domain, "v1", 10, cfg, Set.of());

        assertThat(r1).isEqualTo(r2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.BranchAnalyzerTest"
```
Expected: compilation failure — `BranchAnalyzer` does not exist.

- [ ] **Step 3: Implement `BranchAnalyzer`**

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 3 orchestrator: walks the endpoints from a {@link DomainAnalysisResult},
 * generates {@link NamedSampleInput}s per endpoint, and wraps them into
 * {@link ExploredPath}s — applying the per-endpoint cap and the exclude-paths
 * filter the orchestrator uses for coverage-feedback re-iteration.
 */
public final class BranchAnalyzer {

    private BranchAnalyzer() {}

    public static BranchAnalysisResult analyze(
            DomainAnalysisResult domain,
            String codeVersion,
            int maxPathsPerEndpoint,
            BoundaryValueConfig cfg,
            Set<String> excludeEndpointIds) {

        ManualReviewSink.CollectingSink sink = ManualReviewSink.collecting();
        List<ExploredPath> all = new ArrayList<>();

        for (Endpoint ep : domain.endpoints()) {
            if (excludeEndpointIds.contains(ep.id())) continue;

            MethodAnalysis ma = domain.methodAnalyses()
                    .get(ep.handlerClass() + "#" + ep.handlerMethod());

            List<NamedSampleInput> inputs = (ma == null)
                    ? syntheticHappyOnly(ep, sink)
                    : SampleInputGenerator.generate(ep, ma, cfg, sink);

            List<ExploredPath> built = ExploredPathBuilder.build(ep, inputs, codeVersion);
            if (built.size() > maxPathsPerEndpoint) {
                built = built.subList(0, maxPathsPerEndpoint);
            }
            all.addAll(built);
        }
        return new BranchAnalysisResult(all, sink.frozen());
    }

    private static List<NamedSampleInput> syntheticHappyOnly(Endpoint ep, ManualReviewSink sink) {
        sink.accept(new ManualReviewItem(
                "missing_method_analysis",
                "endpoint handler not present in DomainAnalysisResult.methodAnalyses",
                ep.handlerClass() + "#" + ep.handlerMethod()));
        int status = ep.method() == HttpMethod.POST ? 201 : 200;
        return List.of(new NamedSampleInput("happy", status,
                new SampleInput(Map.of(), Map.of(), Map.of(), null)));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.BranchAnalyzerTest"
```
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/BranchAnalyzer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/BranchAnalyzerTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): BranchAnalyzer — endpoints → BranchAnalysisResult

Walks DomainAnalysisResult.endpoints in order, looks up MethodAnalysis by
{class}#{method} key, routes to SampleInputGenerator or to a synthetic happy
path when the analysis is missing (with a manual-review queue entry), wraps
into ExploredPaths via ExploredPathBuilder, applies excludePaths filter and
maxPathsPerEndpoint cap (happy first).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `StaticAnalysisPathExplorer` + tests

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/StaticAnalysisPathExplorer.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/StaticAnalysisPathExplorerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.exploration.ExplorationBudget;
import io.graphrag.builder.staticanalysis.domain.CallGraph;
import io.graphrag.builder.staticanalysis.domain.ClassRole;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.builder.staticanalysis.domain.Parameter;
import io.graphrag.builder.staticanalysis.domain.ReturnType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAnalysisPathExplorerTest {

    private static final ExplorationBudget BUDGET = new ExplorationBudget(10, Duration.ofSeconds(5));

    private static Endpoint ep() {
        return new Endpoint("GET:/owners/{id}", HttpMethod.GET, "/owners/{id}",
                "petclinic", "com.x.OwnerCtl", "get", false, List.of());
    }

    private static DomainAnalysisResult domain(Endpoint endpoint) {
        Parameter id = new Parameter("id", "Integer", List.of("PathVariable"));
        return new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(endpoint.handlerClass() + "#" + endpoint.handlerMethod(),
                        new MethodAnalysis(endpoint.handlerClass(), endpoint.handlerMethod(),
                                List.of(id), List.of(), List.of(), ReturnType.of("void"))),
                CallGraph.empty());
    }

    @Test
    void name_returns_static_ast() {
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(domain(ep()), BoundaryValueConfig.defaults());
        assertThat(explorer.name()).isEqualTo("static-ast");
    }

    @Test
    void proposeInputs_returns_happy_first() {
        Endpoint endpoint = ep();
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(domain(endpoint), BoundaryValueConfig.defaults());
        List<SampleInput> inputs = explorer.proposeInputs(endpoint, BUDGET);
        assertThat(inputs).isNotEmpty();
        assertThat(inputs.get(0).pathParams()).containsExactly(Map.entry("id", "1"));
    }

    @Test
    void proposeInputs_respects_budget_maxInputs() {
        Endpoint endpoint = ep();
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(domain(endpoint), BoundaryValueConfig.defaults());
        List<SampleInput> inputs =
                explorer.proposeInputs(endpoint, new ExplorationBudget(2, Duration.ofSeconds(5)));
        assertThat(inputs).hasSize(2);
    }

    @Test
    void proposeInputs_deterministic() {
        Endpoint endpoint = ep();
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(domain(endpoint), BoundaryValueConfig.defaults());
        List<SampleInput> r1 = explorer.proposeInputs(endpoint, BUDGET);
        List<SampleInput> r2 = explorer.proposeInputs(endpoint, BUDGET);
        assertThat(r1).isEqualTo(r2);
    }

    @Test
    void proposeInputs_returns_empty_when_method_analysis_missing() {
        Endpoint endpoint = ep();
        DomainAnalysisResult empty = new DomainAnalysisResult(
                List.of(endpoint),
                Map.of(endpoint.handlerClass(), ClassRole.CONTROLLER),
                Map.of(),
                CallGraph.empty());
        StaticAnalysisPathExplorer explorer =
                new StaticAnalysisPathExplorer(empty, BoundaryValueConfig.defaults());
        assertThat(explorer.proposeInputs(endpoint, BUDGET)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.StaticAnalysisPathExplorerTest"
```
Expected: compilation failure — `StaticAnalysisPathExplorer` does not exist.

- [ ] **Step 3: Implement `StaticAnalysisPathExplorer`**

```java
package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.builder.exploration.ExplorationBudget;
import io.graphrag.builder.exploration.PathExplorer;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;
import io.graphrag.model.Endpoint;
import io.graphrag.model.SampleInput;

import java.util.List;
import java.util.Objects;

/**
 * {@link PathExplorer} SPI implementation that surfaces the per-endpoint inputs
 * produced by {@link SampleInputGenerator}. Distinct from {@link BranchAnalyzer}
 * in that it returns only the {@link SampleInput} list (no slug / status / queue
 * — those are internal to the JSON file pipeline). Returns an empty list if the
 * endpoint's handler has no {@link MethodAnalysis} entry.
 */
public final class StaticAnalysisPathExplorer implements PathExplorer {

    private final DomainAnalysisResult domain;
    private final BoundaryValueConfig config;

    public StaticAnalysisPathExplorer(DomainAnalysisResult domain, BoundaryValueConfig config) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override public String name() { return "static-ast"; }

    @Override
    public List<SampleInput> proposeInputs(Endpoint endpoint, ExplorationBudget budget) {
        MethodAnalysis ma = domain.methodAnalyses()
                .get(endpoint.handlerClass() + "#" + endpoint.handlerMethod());
        if (ma == null) return List.of();

        List<NamedSampleInput> generated = SampleInputGenerator.generate(
                endpoint, ma, config, ManualReviewSink.discarding());
        int cap = Math.min(generated.size(), budget.maxInputs());
        return generated.subList(0, cap).stream().map(NamedSampleInput::input).toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.StaticAnalysisPathExplorerTest"
```
Expected: PASS, 5 tests.

- [ ] **Step 5: Run all branch tests as a regression check**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.branch.*"
```
Expected: PASS — accumulated tests across Tasks 3-7.

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/StaticAnalysisPathExplorer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/branch/StaticAnalysisPathExplorerTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): StaticAnalysisPathExplorer — PathExplorer SPI impl

name()="static-ast"; proposeInputs delegates to SampleInputGenerator and
discards the manual-review queue (orchestration path goes through
BranchAnalyzer). Respects ExplorationBudget.maxInputs, returns empty when
the endpoint handler has no MethodAnalysis.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Scaffold `cli/` package + `StaticAnalysisOptions` + parser + tests

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/package-info.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisOptions.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisOptionsParser.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisOptionsParserTest.java`

- [ ] **Step 1: Create `package-info.java`**

```java
/**
 * Standalone CLI entry point for the static analyzer. Wraps
 * {@code AstParser → DomainAnalyzer → BranchAnalyzer} and writes
 * {@code endpoints.json} + {@code paths.json} + {@code static-analysis-report.json}
 * to an output directory specified on the command line.
 */
package io.graphrag.builder.staticanalysis.cli;
```

- [ ] **Step 2: Create `StaticAnalysisOptions.java`**

```java
package io.graphrag.builder.staticanalysis.cli;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Parsed CLI arguments for {@link StaticAnalysisCli}.
 *
 * @param sutSource           required — root of the SUT's {@code src/main/java}
 * @param project             required — project identifier stamped into Endpoints
 * @param out                 required — output directory for the three JSON files
 * @param codeVersion         optional, defaults to {@code "static-1"}
 * @param maxPathsPerEndpoint optional, defaults to {@code 10}
 * @param excludePaths        optional, defaults to empty set
 */
public record StaticAnalysisOptions(
        Path sutSource,
        String project,
        Path out,
        String codeVersion,
        int maxPathsPerEndpoint,
        Set<String> excludePaths) {

    public StaticAnalysisOptions {
        Objects.requireNonNull(sutSource, "sutSource");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(codeVersion, "codeVersion");
        if (maxPathsPerEndpoint < 0)
            throw new IllegalArgumentException("maxPathsPerEndpoint must be >= 0");
        excludePaths = Set.copyOf(Objects.requireNonNull(excludePaths, "excludePaths"));
    }
}
```

- [ ] **Step 3: Write the failing test for the parser**

`graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisOptionsParserTest.java`:

```java
package io.graphrag.builder.staticanalysis.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticAnalysisOptionsParserTest {

    @Test
    void parses_required_flags() {
        StaticAnalysisOptions opts = StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/petclinic",
                "--project", "petclinic",
                "--out", "/tmp/out"
        });
        assertThat(opts.sutSource()).isEqualTo(Path.of("/tmp/petclinic"));
        assertThat(opts.project()).isEqualTo("petclinic");
        assertThat(opts.out()).isEqualTo(Path.of("/tmp/out"));
    }

    @Test
    void defaults_code_version_and_max_paths() {
        StaticAnalysisOptions opts = StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p",
                "--project", "p",
                "--out", "/tmp/o"
        });
        assertThat(opts.codeVersion()).isEqualTo("static-1");
        assertThat(opts.maxPathsPerEndpoint()).isEqualTo(10);
        assertThat(opts.excludePaths()).isEmpty();
    }

    @Test
    void rejects_unknown_flag() {
        assertThatThrownBy(() -> StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p", "--bogus", "x"
        })).isInstanceOf(IllegalArgumentException.class)
           .hasMessageContaining("--bogus");
    }

    @Test
    void rejects_missing_required_flag() {
        assertThatThrownBy(() -> StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p", "--project", "p"
        })).isInstanceOf(IllegalArgumentException.class)
           .hasMessageContaining("--out");
    }

    @Test
    void parses_exclude_paths_csv() {
        StaticAnalysisOptions opts = StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p",
                "--project", "p",
                "--out", "/tmp/o",
                "--exclude-paths", "GET:/a,POST:/b"
        });
        assertThat(opts.excludePaths()).containsExactlyInAnyOrder("GET:/a", "POST:/b");
    }

    @Test
    void parses_max_paths_per_endpoint() {
        StaticAnalysisOptions opts = StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p",
                "--project", "p",
                "--out", "/tmp/o",
                "--max-paths-per-endpoint", "5"
        });
        assertThat(opts.maxPathsPerEndpoint()).isEqualTo(5);
    }

    @Test
    void usage_contains_all_flag_names() {
        String usage = StaticAnalysisOptionsParser.usage();
        assertThat(usage)
                .contains("--sut-source")
                .contains("--project")
                .contains("--out")
                .contains("--code-version")
                .contains("--max-paths-per-endpoint")
                .contains("--exclude-paths");
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.cli.StaticAnalysisOptionsParserTest"
```
Expected: compilation failure — `StaticAnalysisOptionsParser` does not exist.

- [ ] **Step 5: Implement `StaticAnalysisOptionsParser`**

```java
package io.graphrag.builder.staticanalysis.cli;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Pair-walks argv and validates required flags. */
public final class StaticAnalysisOptionsParser {

    private static final Set<String> REQUIRED = Set.of("--sut-source", "--project", "--out");
    private static final Set<String> ALLOWED  = Set.of(
            "--sut-source", "--project", "--out",
            "--code-version", "--max-paths-per-endpoint", "--exclude-paths");

    private StaticAnalysisOptionsParser() {}

    public static StaticAnalysisOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--"))
                throw new IllegalArgumentException("unexpected token: " + a);
            if (!ALLOWED.contains(a))
                throw new IllegalArgumentException("unknown flag: " + a);
            if (i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("missing value for " + a);
            flags.put(a, args[++i]);
        }
        for (String req : REQUIRED) {
            if (!flags.containsKey(req))
                throw new IllegalArgumentException("missing required flag " + req);
        }
        int maxPaths;
        try {
            maxPaths = Integer.parseInt(flags.getOrDefault("--max-paths-per-endpoint", "10"));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "--max-paths-per-endpoint must be an integer: " + flags.get("--max-paths-per-endpoint"));
        }
        return new StaticAnalysisOptions(
                Path.of(flags.get("--sut-source")),
                flags.get("--project"),
                Path.of(flags.get("--out")),
                flags.getOrDefault("--code-version", "static-1"),
                maxPaths,
                parseExclude(flags.get("--exclude-paths")));
    }

    private static Set<String> parseExclude(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    public static String usage() {
        return """
                usage:
                  java -cp graph-rag-builder.jar io.graphrag.builder.staticanalysis.cli.StaticAnalysisCli \\
                    --sut-source <src/main/java root> \\
                    --project    <project name> \\
                    --out        <output dir> \\
                    [--code-version <sha>] \\
                    [--max-paths-per-endpoint <N>] \\
                    [--exclude-paths id1,id2,...]
                """;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.cli.StaticAnalysisOptionsParserTest"
```
Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/ \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisOptionsParserTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): cli scaffold + StaticAnalysisOptions(+Parser)

Pair-walks argv, validates required flags (--sut-source / --project / --out),
defaults --code-version to "static-1" and --max-paths-per-endpoint to 10,
accepts CSV --exclude-paths. Throws IllegalArgumentException on unknown or
missing-value flags so the CLI shell maps to exit code 2.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: `StaticAnalysisReport` record

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisReport.java`

This is a pure data carrier — its serialization is exercised by `StaticAnalysisCliTest` in Task 10.

- [ ] **Step 1: Create `StaticAnalysisReport.java`**

```java
package io.graphrag.builder.staticanalysis.cli;

import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult;
import io.graphrag.builder.staticanalysis.branch.ManualReviewItem;
import io.graphrag.builder.staticanalysis.domain.ClassRole;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.MethodAnalysis;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * JSON shape written to {@code static-analysis-report.json} — execution
 * metadata + parse / analysis / generation counts + the manual-review queue.
 */
public record StaticAnalysisReport(
        String executionTimestamp,
        long executionDurationMs,
        String codeVersion,
        String project,
        Parsing parsing,
        Analysis analysis,
        PathGeneration pathGeneration,
        List<ManualReviewItem> manualReviewQueue) {

    public StaticAnalysisReport {
        Objects.requireNonNull(executionTimestamp, "executionTimestamp");
        Objects.requireNonNull(codeVersion, "codeVersion");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(parsing, "parsing");
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(pathGeneration, "pathGeneration");
        manualReviewQueue = List.copyOf(Objects.requireNonNull(manualReviewQueue, "manualReviewQueue"));
    }

    public record Parsing(int filesScanned, int filesParsed, int filesFailed,
                          List<ParseFailureItem> failures) {
        public Parsing {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }
    }

    public record ParseFailureItem(String path, String message) {
        public ParseFailureItem {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(message, "message");
        }
    }

    public record Analysis(int endpointsFound,
                           int controllersFound, int servicesFound,
                           int repositoriesFound, int domainsFound,
                           int branchesIdentified) {}

    public record PathGeneration(int totalPathsGenerated,
                                 int happyPaths, int boundaryPaths) {}

    public static StaticAnalysisReport from(AstParseResult ast,
                                            DomainAnalysisResult domain,
                                            BranchAnalysisResult branch,
                                            StaticAnalysisOptions opts,
                                            long durationMs) {
        int filesScanned = ast.parsedFiles().size() + ast.failures().size();
        List<ParseFailureItem> failureItems = ast.failures().stream()
                .map(f -> new ParseFailureItem(f.sourcePath().toString(), f.message()))
                .toList();

        int controllers = (int) domain.classRoles().values().stream()
                .filter(r -> r == ClassRole.CONTROLLER).count();
        int services    = (int) domain.classRoles().values().stream()
                .filter(r -> r == ClassRole.SERVICE).count();
        int repositories = (int) domain.classRoles().values().stream()
                .filter(r -> r == ClassRole.REPOSITORY).count();
        int domains     = (int) domain.classRoles().values().stream()
                .filter(r -> r == ClassRole.DOMAIN).count();
        int branchesIdentified = domain.methodAnalyses().values().stream()
                .mapToInt(m -> m.branches().size()).sum();

        long happy = branch.paths().stream()
                .filter(p -> p.id().endsWith("_happy")).count();
        long boundary = branch.paths().size() - happy;

        return new StaticAnalysisReport(
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                durationMs,
                opts.codeVersion(),
                opts.project(),
                new Parsing(filesScanned, ast.parsedFiles().size(),
                        ast.failures().size(), failureItems),
                new Analysis(domain.endpoints().size(),
                        controllers, services, repositories, domains,
                        branchesIdentified),
                new PathGeneration(branch.paths().size(),
                        (int) happy, (int) boundary),
                branch.manualReviewQueue());
    }
}
```

- [ ] **Step 2: Verify compile**

Run:
```bash
gw :graph-rag-builder:compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisReport.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): StaticAnalysisReport — report.json record

ISO-8601 timestamp + duration + parsing/analysis/pathGeneration counts +
manualReviewQueue. Counts derived from AstParseResult + DomainAnalysisResult
+ BranchAnalysisResult; happy vs boundary split by ExploredPath.id suffix.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: `StaticAnalysisCli` main + integration test

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisCli.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisCliTest.java`

The integration test runs the CLI's `run(...)` entry point on the petclinic fixture committed in Task 1 (fixture already exists from the T1+T2 session).

- [ ] **Step 1: Write the failing integration test**

`graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisCliTest.java`:

```java
package io.graphrag.builder.staticanalysis.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAnalysisCliTest {

    private static Path fixture() {
        return Path.of("src/test/resources/staticanalysis/petclinic-fixture");
    }

    private static int run(String[] args, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        return StaticAnalysisCli.run(args, new PrintStream(out), new PrintStream(err));
    }

    @Test
    void cli_writes_three_json_files(@TempDir Path tmp) {
        Path outDir = tmp.resolve("archive");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, out, err);
        assertThat(code).isZero();
        assertThat(Files.exists(outDir.resolve("endpoints.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("paths.json"))).isTrue();
        assertThat(Files.exists(outDir.resolve("static-analysis-report.json"))).isTrue();
    }

    @Test
    void endpoints_json_parses_as_endpoint_list(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        List<Endpoint> endpoints = m.readValue(
                outDir.resolve("endpoints.json").toFile(),
                new TypeReference<>() {});
        assertThat(endpoints).isNotEmpty();
        assertThat(endpoints).extracting(Endpoint::id)
                .allMatch(id -> id.matches("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS):/.+$"));
    }

    @Test
    void paths_json_parses_as_explored_path_list(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        List<ExploredPath> paths = m.readValue(
                outDir.resolve("paths.json").toFile(),
                new TypeReference<>() {});
        assertThat(paths).isNotEmpty();
        assertThat(paths).extracting(ExploredPath::id)
                .allMatch(id -> id.startsWith("static_"));
    }

    @Test
    void report_json_has_expected_top_level_keys(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        Map<String, Object> report = m.readValue(
                outDir.resolve("static-analysis-report.json").toFile(),
                new TypeReference<>() {});
        assertThat(report).containsKeys(
                "executionTimestamp", "executionDurationMs",
                "codeVersion", "project",
                "parsing", "analysis", "pathGeneration", "manualReviewQueue");
    }

    @Test
    void each_endpoint_has_at_least_one_path(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString()
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        List<Endpoint> endpoints = m.readValue(
                outDir.resolve("endpoints.json").toFile(), new TypeReference<>() {});
        List<ExploredPath> paths = m.readValue(
                outDir.resolve("paths.json").toFile(), new TypeReference<>() {});
        for (Endpoint ep : endpoints) {
            assertThat(paths).as("paths for endpoint %s", ep.id())
                    .anyMatch(p -> p.endpointId().equals(ep.id()));
        }
    }

    @Test
    void excludePaths_argument_filters_output(@TempDir Path tmp) throws Exception {
        Path outDir = tmp.resolve("archive");
        run(new String[] {
                "--sut-source", fixture().toString(),
                "--project", "petclinic",
                "--out", outDir.toString(),
                "--exclude-paths", "GET:/owners"
        }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        ObjectMapper m = JsonMappers.standard();
        List<Endpoint> endpoints = m.readValue(
                outDir.resolve("endpoints.json").toFile(), new TypeReference<>() {});
        List<ExploredPath> paths = m.readValue(
                outDir.resolve("paths.json").toFile(), new TypeReference<>() {});
        assertThat(endpoints).extracting(Endpoint::id).doesNotContain("GET:/owners");
        assertThat(paths).extracting(ExploredPath::endpointId).doesNotContain("GET:/owners");
    }

    @Test
    void idempotent_two_runs_same_bytes_for_endpoints_and_paths(@TempDir Path tmp) throws Exception {
        Path runA = tmp.resolve("a");
        Path runB = tmp.resolve("b");
        for (Path d : new Path[] { runA, runB }) {
            run(new String[] {
                    "--sut-source", fixture().toString(),
                    "--project", "petclinic",
                    "--out", d.toString()
            }, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        }
        assertThat(Files.readAllBytes(runA.resolve("endpoints.json")))
                .isEqualTo(Files.readAllBytes(runB.resolve("endpoints.json")));
        assertThat(Files.readAllBytes(runA.resolve("paths.json")))
                .isEqualTo(Files.readAllBytes(runB.resolve("paths.json")));
    }

    @Test
    void missing_required_flag_exits_2(@TempDir Path tmp) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(new String[] { "--sut-source", fixture().toString() },
                new ByteArrayOutputStream(), err);
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("--project").contains("usage");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.cli.StaticAnalysisCliTest"
```
Expected: compilation failure — `StaticAnalysisCli` does not exist.

- [ ] **Step 3: Implement `StaticAnalysisCli`**

```java
package io.graphrag.builder.staticanalysis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.builder.staticanalysis.branch.BoundaryValueConfig;
import io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult;
import io.graphrag.builder.staticanalysis.branch.BranchAnalyzer;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.DomainAnalyzer;
import io.graphrag.model.JsonMappers;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;

/**
 * CLI entry point for the static analyzer. Invoked via
 * {@code java -cp graph-rag-builder.jar io.graphrag.builder.staticanalysis.cli.StaticAnalysisCli ...}.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — success, three JSON files written</li>
 *   <li>2 — flag parsing / usage error</li>
 *   <li>1 — runtime error (IO, etc.)</li>
 * </ul>
 */
public final class StaticAnalysisCli {

    private static final ObjectMapper M = JsonMappers.standard();

    private StaticAnalysisCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        StaticAnalysisOptions opts;
        try {
            opts = StaticAnalysisOptionsParser.parse(args);
        } catch (IllegalArgumentException ex) {
            err.println("error: " + ex.getMessage());
            err.println(StaticAnalysisOptionsParser.usage());
            return 2;
        }
        try {
            long startNanos = System.nanoTime();
            AstParseResult ast = AstParser.parse(opts.sutSource());
            DomainAnalysisResult domain = DomainAnalyzer.analyze(ast, opts.project());
            BranchAnalysisResult branch = BranchAnalyzer.analyze(
                    domain, opts.codeVersion(), opts.maxPathsPerEndpoint(),
                    BoundaryValueConfig.defaults(), opts.excludePaths());
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            StaticAnalysisReport report =
                    StaticAnalysisReport.from(ast, domain, branch, opts, durationMs);

            Files.createDirectories(opts.out());
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(opts.out().resolve("endpoints.json").toFile(),
                            domain.endpoints());
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(opts.out().resolve("paths.json").toFile(),
                            branch.paths());
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(opts.out().resolve("static-analysis-report.json").toFile(),
                            report);

            out.println("[static-analysis] "
                    + domain.endpoints().size() + " endpoint(s), "
                    + branch.paths().size() + " path(s) → "
                    + opts.out().toAbsolutePath());
            return 0;
        } catch (IOException ex) {
            err.println("error: " + ex.getMessage());
            return 1;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.cli.StaticAnalysisCliTest"
```
Expected: PASS, 8 tests. If `endpoints_json_parses_as_endpoint_list` fails because `JsonMappers` rejects unknown subfields, inspect the failure message and adjust — but the existing T1+T2 path uses the same mapper and the fixture has been validated, so this should pass first try.

- [ ] **Step 5: Run all cli tests as a regression check**

Run:
```bash
gw :graph-rag-builder:test --tests "io.graphrag.builder.staticanalysis.cli.*"
```
Expected: PASS — Task 8 + Task 10 combined.

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/staticanalysis/cli/StaticAnalysisCliTest.java
git commit -m "$(cat <<'EOF'
feat(staticanalysis): StaticAnalysisCli — end-to-end JSON pipeline

Wires AstParser → DomainAnalyzer → BranchAnalyzer, writes endpoints.json,
paths.json, and static-analysis-report.json to --out. Integration test runs
the CLI on the existing petclinic fixture: 5 endpoints, ≥1 path per endpoint,
idempotent across runs, --exclude-paths filters output, missing-required-flag
exits 2.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Switch `IterationRunner` to the new analyzer

**Files:**
- Modify: `orchestrator/build.gradle.kts`
- Modify: `orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java`

The current `orchestrator/build.gradle.kts` lists `implementation(project(":path-discovery-static"))`; the new one swaps it for `implementation(project(":graph-rag-builder"))`. `OrchestratorE2eTest` becomes the regression check — no new test code.

- [ ] **Step 1: Add `:graph-rag-builder` dependency to orchestrator**

Edit `orchestrator/build.gradle.kts`. Replace
```kotlin
    implementation(project(":path-discovery-static"))
```
with
```kotlin
    implementation(project(":graph-rag-builder"))
```

After the edit, the `dependencies` block should read:
```kotlin
dependencies {
    implementation(project(":shared-model"))
    implementation(project(":graph-rag-builder"))
    implementation(project(":scout-step-translator"))
    implementation(project(":scout-launcher"))
    implementation(project(":coverage-feedback"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.0")
}
```

- [ ] **Step 2: Rewrite the Stage-1 block of `IterationRunner.runOne`**

Open `orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java`.

Replace this import:
```java
import io.graphrag.discovery.PathDiscoveryStatic;
```
with these:
```java
import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.builder.staticanalysis.branch.BoundaryValueConfig;
import io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult;
import io.graphrag.builder.staticanalysis.branch.BranchAnalyzer;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.DomainAnalyzer;
```

Replace this block:
```java
        log.println("\n=== iter " + iterIndex + " — Stage 1 (path discovery) ===");
        PathDiscoveryStatic.Result discovery = PathDiscoveryStatic.discover(
                cfg.sutSource(), cfg.project(),
                "iter-" + iterIndex,
                excludePaths);
        Files.createDirectories(layout.stage1Discovery());
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage1Endpoints().toFile(), discovery.endpoints());
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage1Paths().toFile(), discovery.paths());

        if (discovery.endpoints().isEmpty()) {
            log.println("[orchestrator] Stage 1 produced zero endpoints — halting iteration");
            return Outcome.zeroPaths(layout);
        }
```
with:
```java
        log.println("\n=== iter " + iterIndex + " — Stage 1 (path discovery) ===");
        AstParseResult ast = AstParser.parse(cfg.sutSource());
        DomainAnalysisResult domain = DomainAnalyzer.analyze(ast, cfg.project());
        BranchAnalysisResult branch = BranchAnalyzer.analyze(
                domain,
                "iter-" + iterIndex,
                /* maxPathsPerEndpoint */ 10,
                BoundaryValueConfig.defaults(),
                excludePaths);
        Files.createDirectories(layout.stage1Discovery());
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage1Endpoints().toFile(), domain.endpoints());
        M.writerWithDefaultPrettyPrinter()
                .writeValue(layout.stage1Paths().toFile(), branch.paths());

        if (domain.endpoints().isEmpty()) {
            log.println("[orchestrator] Stage 1 produced zero endpoints — halting iteration");
            return Outcome.zeroPaths(layout);
        }
```

Then update the only other reference in `runOne`:

Replace
```java
        List<String> endpointIds = discovery.endpoints().stream().map(Endpoint::id).toList();
```
with
```java
        List<String> endpointIds = domain.endpoints().stream().map(Endpoint::id).toList();
```

- [ ] **Step 3: Run the orchestrator E2E**

Run:
```bash
gw :orchestrator:test --tests "io.graphrag.orchestrator.OrchestratorE2eTest"
```
Expected: PASS — both `single_iteration_target_reached_terminates_and_writes_report` and `max_iterations_cap_stops_when_no_progress_and_target_unreached`.

If a test fails because `BranchAnalyzer` produces a different path count than `PathDiscoveryStatic` and the fake JaCoCo XMLs assume coverage signatures from the old code, inspect the failure with `gw :orchestrator:test --tests "...OrchestratorE2eTest" --info` and adjust — but the FakeExternal only writes a static JaCoCo blob; it does not inspect the paths, so this should pass first try.

- [ ] **Step 4: Run the full orchestrator test suite**

Run:
```bash
gw :orchestrator:test
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add orchestrator/build.gradle.kts orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java
git commit -m "$(cat <<'EOF'
feat(orchestrator): switch Stage 1 to graph-rag-builder static analyzer

Replaces the in-process call to PathDiscoveryStatic.discover with the new
AstParser → DomainAnalyzer → BranchAnalyzer pipeline. Builds endpoints.json
from DomainAnalysisResult.endpoints and paths.json from
BranchAnalysisResult.paths. OrchestratorE2eTest stays GREEN (both target-
reached and max-iterations termination cases).

:orchestrator now depends on :graph-rag-builder instead of
:path-discovery-static; the latter is removed in the following commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Delete the `:path-discovery-static` module

**Files:**
- Modify: `settings.gradle.kts`
- Delete: `path-discovery-static/` (entire directory)

The orchestrator no longer depends on it; we'll grep the rest of the repo for stray references, remove the directory, drop the `include` entry, and verify the full build.

- [ ] **Step 1: Confirm no other module references `:path-discovery-static`**

Run:
```bash
grep -RIl "path-discovery-static" --include="*.kts" --include="*.gradle" --include="*.java" . | grep -v node_modules
```
Expected: only `settings.gradle.kts`, `path-discovery-static/` files themselves, `docs/` references, and possibly `MEMORY.md` / handoff notes. If any other source under `*/src/**` references it, stop and investigate before proceeding — that's a hidden dependency.

- [ ] **Step 2: Remove the module directory**

Run:
```bash
git rm -r path-discovery-static
```

- [ ] **Step 3: Remove the `include` entry from `settings.gradle.kts`**

Open `settings.gradle.kts`. Remove the line:
```kotlin
    ":path-discovery-static",
```
from inside the `include(...)` block. The trailing comma on the line above (`:scout-step-translator`) stays — it's a Kotlin list literal.

After the edit, the relevant block reads:
```kotlin
include(
    ":shared-model",
    ":testlib-api",
    ":testlib-adapter-noop",
    ":test-state-dashboard",
    ":socket-mock-server",
    ":graph-rag-builder",
    ":test-generator",
    ":socket-capture-agent",
    ":scout-launcher",
    ":scout-step-translator",
    ":coverage-feedback",
    ":orchestrator",
)
```

- [ ] **Step 4: Confirm grep is clean**

Run:
```bash
grep -RIl "path-discovery-static" --include="*.kts" --include="*.gradle" --include="*.java" . | grep -v "^./docs/" | grep -v MEMORY.md | grep -v ".remember/" | grep -v "/handoff/"
```
Expected: empty (or only legacy docs / handoff notes that intentionally mention the old module).

- [ ] **Step 5: Full repo regression**

Run:
```bash
gw check
```
Expected: `BUILD SUCCESSFUL`. If a different module had a stray reference, the build will fail with `Project ':path-discovery-static' not found` — go back to Step 1 and fix.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts path-discovery-static
git commit -m "$(cat <<'EOF'
chore(staticanalysis): remove :path-discovery-static module

Stage 1 is now driven entirely by graph-rag-builder's staticanalysis package;
the original PathDiscoveryStatic scanner has no remaining consumers. Drops
the module directory and removes the include() entry from settings.gradle.kts.
Full repo build stays GREEN.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Final regression run

**Files:** (none — verification only)

- [ ] **Step 1: Full `:graph-rag-builder:test` run**

Run:
```bash
gw :graph-rag-builder:test
```
Expected: every existing test (T1+T2 unit + integration, `JdbcAgentBaggageBridgeTest`, `MyBatisDynamicSqlInterceptorTest`, all capture / store / persistence tests) plus the new branch + cli tests GREEN. **No failures, no errors.**

- [ ] **Step 2: Full `:orchestrator:test` run**

Run:
```bash
gw :orchestrator:test
```
Expected: `OrchestratorE2eTest` PASS — both methods.

- [ ] **Step 3: Full repo `check`**

Run:
```bash
gw check
```
Expected: `BUILD SUCCESSFUL`, every module's tests GREEN. If only `:graph-rag-builder` shows tests, every other module's compile is implicitly verified — that's the point of `check`.

- [ ] **Step 4: Verify acceptance criteria from spec §13**

Spot-check each acceptance criterion:

| Criterion | Verify by |
|---|---|
| `branch/` package builds + tests GREEN | Step 1 output |
| `cli/` package builds + `StaticAnalysisCliTest` runs CLI on fixture | Step 1 output (it ran) |
| Each endpoint in `paths.json` has ≥1 `ExploredPath` | Asserted in `each_endpoint_has_at_least_one_path` |
| `:graph-rag-builder:test` GREEN no regressions | Step 1 |
| `IterationRunner` uses `BranchAnalyzer` | `grep -n BranchAnalyzer.analyze orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java` returns a hit |
| `OrchestratorE2eTest` GREEN | Step 2 |
| `path-discovery-static/` removed; `settings.gradle.kts` clean | `ls path-discovery-static 2>&1` should report "no such file"; `grep path-discovery-static settings.gradle.kts` returns nothing |
| Full repo `check` GREEN | Step 3 |

If any criterion fails, fix in a follow-up commit before moving on.

- [ ] **Step 5: No final commit (verification-only task)**

This task verifies; it does not modify. If you found and fixed something in Step 4, that fix gets its own commit message, not amended to a prior task.

---

## Self-Review notes

Manual spec ↔ plan crosswalk:

- Spec §3.1 BoundaryValueConfig → Task 2
- Spec §3.2 BoundaryValueGenerator → Task 3
- Spec §3.3 SampleInputGenerator → Task 4
- Spec §3.4 ExploredPathBuilder → Task 5
- Spec §3.5 BranchAnalyzer → Task 6
- Spec §3.6 StaticAnalysisPathExplorer → Task 7
- Spec §3.7 T3 acceptance criteria → distributed across Tasks 3–7 unit tests + Task 10 integration test
- Spec §4.1–4.4 CLI surface → Task 8 (options + parser), Task 9 (report), Task 10 (cli main + integration)
- Spec §4.5 CLI acceptance → Task 10 integration test
- Spec §5.1 IterationRunner change → Task 11
- Spec §5.2 module deletion → Task 12
- Spec §5.3 orchestrator-switch acceptance → Task 11 Step 3 + Task 13 Step 2
- Spec §13 session acceptance → Task 13 Step 4

Out-of-scope items confirmed not implemented:
- Real-petclinic E2E (handoff §6) — none of the tasks reference `mvn`, Postgres, or the spring-petclinic clone.
- Nice-to-have T3 variants (`@NotNull` null, enum permutations, `@ExceptionHandler` exitStatus inference, `PathConstraintBuilder`) — none touched; documented as TODO in spec §1.2.
- `MethodAnalysis.outgoingCalls` population — left as `List.of()` from T2; not modified.
- `BranchKind.RETURN` emission — left unused.
- `shared-model` changes — none of the tasks touch `shared-model/`.
