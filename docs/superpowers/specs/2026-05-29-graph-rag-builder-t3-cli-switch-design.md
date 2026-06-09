# graph-rag-builder Static Analysis — T3 + CLI + Orchestrator Switch Design

> Spec for the T3 branch-analysis + SampleInput-generation, CLI entry point, and
> orchestrator switch portions of `graph-rag-builder-static-analysis-work-order.md`
> (rev.1) — picks up exactly where the T1+T2 spec (2026-05-28) left off.
>
> Date: 2026-05-29
> Branch: feat/t6-orchestrator (continues from `ae021b6`)
> Author: assistant (Claude Opus 4.7)

## 1. Scope

This spec covers the three deliverables left over from the T1+T2 session:

1. **T3** — `staticanalysis/branch/` package: deterministic boundary-value
   `SampleInput` generation per endpoint + `StaticAnalysisPathExplorer` SPI impl.
2. **CLI** — `staticanalysis/cli/` package: standalone CLI entry point that
   writes `endpoints.json` + `paths.json` + `static-analysis-report.json`.
3. **Orchestrator switch** — `orchestrator/IterationRunner` calls the new
   in-process API (`AstParser` → `DomainAnalyzer` → `BranchAnalyzer`) instead
   of `PathDiscoveryStatic.discover(...)`; `:path-discovery-static` module is
   deleted in a follow-up commit.

### 1.1 Decisions confirmed for this session

| Decision | Resolution |
|---|---|
| Orchestrator integration mode | **Both** — in-process API for the orchestrator AND a CLI for standalone usage (work order §9). They share the same `BranchAnalyzer` core. |
| T3 scope | **Must-have only** — happy path + 1 boundary-value variant per numeric/string param. Nice-to-have items (full boundary set, `@NotNull` → null, enum permutations, `@ExceptionHandler` → exitStatus inference, `PathConstraintBuilder`) documented as TODO and skipped. |
| `PathExplorerKind` for static-AST | **Reuse `MANUAL`** (work order §11 recommendation). No `shared-model` change. |
| `StaticAnalysisPathExplorer` location | **`staticanalysis/branch/`** per work order §8.4.4, not `exploration/` next to `ManualPathExplorer`. |
| `path-discovery-static` deletion timing | After the orchestrator switch is in place and `OrchestratorE2eTest` GREEN, delete in a **separate commit** (clean diff). |
| Build flag | `-Pagent.enabled=true` for all builds (unchanged from T1+T2 session). |

### 1.2 Out of scope (deferred to a later session)

- Real-petclinic E2E (requires `mvn -DskipTests package`, Postgres docker-compose,
  Stage-5 wrapper to copy generated tests into petclinic's test source tree, etc).
  See handoff §6.
- Nice-to-have T3 features (full boundary set, `@NotNull` null variant, enum
  permutations, `@ExceptionHandler` exitStatus inference, `PathConstraintBuilder`
  symbolic-execution territory).
- `MethodAnalysis.outgoingCalls` population — currently `List.of()`. T3
  must-have scope doesn't use it; deferred.
- `BranchKind.RETURN` emission — currently unused; deferred. IF/SWITCH parent
  extraction already covers the deterministic-branch cases needed for v1.
- `shared-model` changes (no new `PathExplorerKind` value).
- Default-build (`-Pagent.enabled=false`) `JdbcAgentBaggageBridge` compile error
  in `ArchiveShutdownWriter.java` — pre-existing, out of scope.

## 2. Package layout

```
graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/
├── ast/                                  [EXISTING T1 — unchanged]
├── domain/                               [EXISTING T2 — unchanged]
├── branch/                               [NEW T3]
│   ├── package-info.java
│   ├── BoundaryValueConfig.java          record — per-type rules (v1 ships static defaults)
│   ├── BoundaryValueGenerator.java       per-type boundary value sets + isNumeric / isStringLike
│   ├── SampleInputGenerator.java         (Endpoint, MethodAnalysis) → List<NamedSampleInput>
│   ├── NamedSampleInput.java             record (slug, predictedStatus, SampleInput) — package-private
│   ├── ExploredPathBuilder.java          (Endpoint, List<NamedSampleInput>, codeVersion) → List<ExploredPath>
│   ├── BranchAnalyzer.java               orchestrator: DomainAnalysisResult → BranchAnalysisResult
│   ├── BranchAnalysisResult.java         record (paths, manualReviewQueue)
│   ├── ManualReviewItem.java             record (kind, reason, location)
│   ├── ManualReviewSink.java             functional interface — Consumer<ManualReviewItem> + discarding() factory
│   └── StaticAnalysisPathExplorer.java   PathExplorer SPI impl
└── cli/                                  [NEW]
    ├── package-info.java
    ├── StaticAnalysisOptions.java        record (sutSource, project, out, codeVersion, maxPathsPerEndpoint, excludePaths)
    ├── StaticAnalysisOptionsParser.java  argv → StaticAnalysisOptions (+ usage helper)
    ├── StaticAnalysisReport.java         record matching report JSON schema
    └── StaticAnalysisCli.java            main + JSON writers
```

Test sources mirror the layout under `src/test/java/.../staticanalysis/{branch,cli}`.

## 3. T3 — Branch analysis + SampleInput generation

### 3.1 BoundaryValueConfig

```java
public record BoundaryValueConfig(
        List<String> numericVariants,        // ["-1", "0", String.valueOf(Integer.MAX_VALUE)]
        String numericHappy,                  // "1"
        List<String> stringVariants,         // [""]
        String stringHappy                    // "a"
) {
    public static BoundaryValueConfig defaults() { /* ... */ }
}
```

Static defaults baked in; the constructor exists so a future session can vary
the boundary set per-project without API churn. v1 always calls `defaults()`.

### 3.2 BoundaryValueGenerator

Pure functions:

```java
public final class BoundaryValueGenerator {
    public static boolean isNumeric(String typeName);       // int, long, Integer, Long, short, byte, double, float, ...
    public static boolean isStringLike(String typeName);    // String, CharSequence
    public static String happy(String typeName, BoundaryValueConfig cfg);
    public static List<String> variants(String typeName, BoundaryValueConfig cfg);  // excludes happy
}
```

Non-numeric and non-string types fall through to `happy(...) = ""` and
`variants(...) = []` — the caller (`SampleInputGenerator`) sees an empty
variant list and emits no boundary variant for that param. A
`ManualReviewItem` of kind `"complex_parameter_type"` is logged in that case.

### 3.3 SampleInputGenerator

```java
public final class SampleInputGenerator {
    public static List<NamedSampleInput> generate(
            Endpoint endpoint,
            MethodAnalysis methodAnalysis,
            BoundaryValueConfig cfg,
            ManualReviewSink sink);
}

record NamedSampleInput(String slug, int predictedStatus, SampleInput input) {}
```

Algorithm:

1. Categorize each `Parameter` by annotations:
   - `@PathVariable` → `pathParams`
   - `@RequestParam` → `queryParams`
   - `@RequestHeader` → `headers`
   - `@RequestBody` → body sentinel (`{}` placeholder)
   - No annotation + `simpleName(type)` in `{Model, BindingResult, HttpServletRequest, ...}` → ignored
   - Otherwise → query param (Spring's default binding)
2. Build the **happy** `NamedSampleInput`:
   - All path/query/header params get `happy(type)` strings.
   - Body gets `{}` when the endpoint declares a `@RequestBody` param, else `null`.
   - `slug = "happy"`, `predictedStatus = happyStatus(endpoint.method())`
     (200 for GET/PUT/DELETE/PATCH; 201 for POST; 200 for HEAD/OPTIONS).
3. For each path/query parameter (in declaration order) whose type is numeric
   or string-like, emit one variant per `variants(type)` entry:
   - Replace **only that param** with the variant value; other params keep
     happy values.
   - `slug = "{paramName}-{slugifyValue(variant)}"` where `slugifyValue`
     follows the existing `path-discovery-static` convention
     (`""` → `"empty"`, `-1` → `"neg1"`, otherwise unchanged).
   - `predictedStatus = 400` if variant is `""`, else `404`.
4. Header parameters do **not** emit boundary variants in v1 (rare in petclinic
   and a frequent false-positive 400 generator).
5. Body parameters do not emit body-field-level boundary variants in v1.

`ManualReviewSink` is a `@FunctionalInterface` wrapping
`Consumer<ManualReviewItem>` with two factories: `collecting()` returns a
sink backed by an `ArrayList` (used by `BranchAnalyzer`) and `discarding()`
returns a no-op sink (used by `StaticAnalysisPathExplorer.proposeInputs`
where the queue is not surfaced).

### 3.4 ExploredPathBuilder

```java
public final class ExploredPathBuilder {
    public static List<ExploredPath> build(
            Endpoint endpoint,
            List<NamedSampleInput> inputs,
            String codeVersion);
}
```

For each `NamedSampleInput`:

```java
new ExploredPath(
    /* id                 */ "static_" + handlerMethodName + "_" + slug,
    /* endpointId         */ endpoint.id(),
    /* discoveredBy       */ PathExplorerKind.MANUAL,
    /* sampleInput        */ input.input(),
    /* pathConstraint     */ null,
    /* branchesTaken      */ List.of(endpoint.handlerClass() + "." + handlerMethodName + ":" + slug),
    /* exitStatus         */ input.predictedStatus(),
    /* exitResponseShape  */ null,
    /* coverageSignature  */ "static:" + endpoint.id() + ":" + slug,
    /* codeVersion        */ codeVersion);
```

`handlerMethodName` is `endpoint.handlerMethod()` (set by `EndpointExtractor` to
the simple method name in T2). Slug + signature convention matches the existing
`path-discovery-static/.../ExploredPathBuilder` so coverage feedback remains
backward-compatible.

### 3.5 BranchAnalyzer

```java
public final class BranchAnalyzer {
    public static BranchAnalysisResult analyze(
            DomainAnalysisResult domain,
            String codeVersion,
            int maxPathsPerEndpoint,
            BoundaryValueConfig cfg,
            Set<String> excludeEndpointIds);
}

public record BranchAnalysisResult(
        List<ExploredPath> paths,
        List<ManualReviewItem> manualReviewQueue) { /* compact ctor copies + freezes */ }

public record ManualReviewItem(String kind, String reason, String location) {}
```

Algorithm:

For each `Endpoint ep` in `domain.endpoints()`, in iteration order:
1. If `excludeEndpointIds.contains(ep.id())`, skip entirely.
2. Look up `MethodAnalysis ma = domain.methodAnalyses().get(ep.handlerClass() + "#" + ep.handlerMethod())`.
3. If `ma == null`, push a `ManualReviewItem("missing_method_analysis", ep.id(), ep.handlerClass() + "#" + ep.handlerMethod())`
   into the sink and emit a single happy `NamedSampleInput` directly (no
   parameters, empty headers/path/query maps, body = `null`,
   `predictedStatus = happyStatus(ep.method())`). Skip steps 4–6 for this
   endpoint.
4. `inputs = SampleInputGenerator.generate(ep, ma, cfg, sink)`.
5. `endpointPaths = ExploredPathBuilder.build(ep, inputs, codeVersion)`.
6. Cap to `maxPathsPerEndpoint`: keep happy first, then boundary variants in
   generation order, drop tail.
7. Append to result paths list.

Output:
- `paths` ordered by endpoint iteration order, then happy-first within
  endpoint.
- `manualReviewQueue` ordered by accumulation order.

Determinism is inherited from `DomainAnalysisResult` (already sorted by `(method, path)`).

### 3.6 StaticAnalysisPathExplorer (PathExplorer SPI)

```java
public final class StaticAnalysisPathExplorer implements PathExplorer {

    private final DomainAnalysisResult domain;
    private final BoundaryValueConfig config;

    public StaticAnalysisPathExplorer(DomainAnalysisResult domain, BoundaryValueConfig config) {
        this.domain = domain;
        this.config = config;
    }

    @Override public String name() { return "static-ast"; }

    @Override
    public List<SampleInput> proposeInputs(Endpoint endpoint, ExplorationBudget budget) {
        MethodAnalysis ma = domain.methodAnalyses().get(endpoint.handlerClass() + "#" + endpoint.handlerMethod());
        if (ma == null) return List.of(/* empty happy */);
        List<NamedSampleInput> inputs = SampleInputGenerator.generate(
                endpoint, ma, config, ManualReviewSink.discarding());
        int cap = Math.min(inputs.size(), budget.maxInputs());
        return inputs.subList(0, cap).stream()
                .map(NamedSampleInput::input)
                .toList();
    }
}
```

`ManualReviewSink.discarding()` exists so the SPI consumer doesn't have to
care about the queue; the queue is only assembled when the CLI / orchestrator
goes through `BranchAnalyzer.analyze`.

### 3.7 T3 acceptance criteria

- [ ] Each endpoint in petclinic fixture → exactly one happy path.
- [ ] `OwnerRestController.getOwner(Integer id)` → happy + 3 numeric variants
      (`id-neg1`, `id-0`, `id-{MAX_INT}`) = 4 paths.
- [ ] `OwnerRestController.createOwner(@RequestBody Owner)` → 1 path (happy
      only — body is `@RequestBody` with no path/query params).
- [ ] `OwnerRestController.deleteOwner(Integer id)` → 4 paths.
- [ ] Determinism: 2× `BranchAnalyzer.analyze(...)` on the same input
      produces identical `paths` lists (records' auto-`equals`).
- [ ] `StaticAnalysisPathExplorer.name()` returns `"static-ast"`.
- [ ] `proposeInputs(ep, budget)` returns `min(generated, budget.maxInputs())`
      items, happy first.
- [ ] `excludeEndpointIds` skips the endpoint entirely (no paths, no manual
      review entries).
- [ ] All boundary `ExploredPath.id` values match `^static_[A-Za-z0-9]+_[a-z0-9-]+$`.
- [ ] `coverageSignature` is unique within a run; equal across two runs on the
      same input.

## 4. CLI

### 4.1 Surface

```
java -cp graph-rag-builder.jar io.graphrag.builder.staticanalysis.cli.StaticAnalysisCli \
    --sut-source <dir>            (required)
    --project    <name>           (required)
    --out        <dir>            (required)
    [--code-version <sha>]        (default: "static-1")
    [--max-paths-per-endpoint N]  (default: 10)
    [--exclude-paths id1,id2]     (default: empty)
```

Outputs to `<out>/`:
- `endpoints.json` — `List<Endpoint>` JSON.
- `paths.json` — `List<ExploredPath>` JSON.
- `static-analysis-report.json` — `StaticAnalysisReport` JSON.

Exit codes: 0 success, 2 flag/usage error, 1 runtime error.

### 4.2 StaticAnalysisOptions

```java
public record StaticAnalysisOptions(
        Path sutSource,
        String project,
        Path out,
        String codeVersion,
        int maxPathsPerEndpoint,
        Set<String> excludePaths) {}
```

Parser is a single static method that walks argv pair-wise and validates
required flags. Rejects unknown flags (exit 2). Same flag-parsing pattern as
`PathDiscoveryStatic.parseFlags` (which is being deleted), so the CLI feels
familiar to anyone who used the old tool.

### 4.3 StaticAnalysisReport

```java
public record StaticAnalysisReport(
        String executionTimestamp,             // ISO-8601 with offset
        long executionDurationMs,
        String codeVersion,
        String project,
        Parsing parsing,
        Analysis analysis,
        PathGeneration pathGeneration,
        List<ManualReviewItem> manualReviewQueue) {

    public record Parsing(int filesScanned, int filesParsed, int filesFailed,
                          List<ParseFailureItem> failures) {}
    public record ParseFailureItem(String path, String message) {}
    public record Analysis(int endpointsFound, int controllersFound, int servicesFound,
                           int repositoriesFound, int domainsFound, int branchesIdentified) {}
    public record PathGeneration(int totalPathsGenerated, int happyPaths, int boundaryPaths) {}
}
```

Counts derived from the `AstParseResult`, `DomainAnalysisResult`, and
`BranchAnalysisResult` after the pipeline runs. `branchesIdentified` is the
sum of `methodAnalyses.values().stream().mapToInt(m -> m.branches().size()).sum()`.
`happyPaths` / `boundaryPaths` are computed by inspecting `ExploredPath.id`
for the `_happy` suffix.

### 4.4 StaticAnalysisCli.run

```java
public static int run(String[] args, PrintStream out, PrintStream err) {
    StaticAnalysisOptions opts;
    try { opts = StaticAnalysisOptionsParser.parse(args); }
    catch (IllegalArgumentException ex) {
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
        StaticAnalysisReport report = StaticAnalysisReport.from(
                ast, domain, branch, opts, durationMs);
        Files.createDirectories(opts.out());
        ObjectMapper m = JsonMappers.standard();
        m.writerWithDefaultPrettyPrinter().writeValue(
                opts.out().resolve("endpoints.json").toFile(), domain.endpoints());
        m.writerWithDefaultPrettyPrinter().writeValue(
                opts.out().resolve("paths.json").toFile(), branch.paths());
        m.writerWithDefaultPrettyPrinter().writeValue(
                opts.out().resolve("static-analysis-report.json").toFile(), report);
        out.println("[static-analysis] " + domain.endpoints().size() + " endpoint(s), "
                + branch.paths().size() + " path(s) → " + opts.out().toAbsolutePath());
        return 0;
    } catch (IOException ex) {
        err.println("error: " + ex.getMessage());
        return 1;
    }
}
```

`main(String[])` delegates to `run(args, System.out, System.err)` then
`System.exit`s the return value.

The CLI does **not** become the Gradle `application` main class for
`:graph-rag-builder` — that module already has `BuilderApplication`. The CLI
is invoked via `java -cp graph-rag-builder.jar
io.graphrag.builder.staticanalysis.cli.StaticAnalysisCli`. A future session can
add a separate `application` task if convenient; not required for v1.

### 4.5 CLI acceptance criteria

- [ ] Missing `--sut-source` → exit 2, usage printed.
- [ ] Unknown flag → exit 2.
- [ ] Successful run on petclinic fixture writes 3 JSON files.
- [ ] `ArchiveReader.load(<out>)` (from `:test-generator`) reads the produced
      `endpoints.json` + `paths.json` without error.
- [ ] `--exclude-paths "GET:/owners"` removes that endpoint from
      `endpoints.json` and all its paths from `paths.json`.
- [ ] Two consecutive runs with identical args produce byte-identical files
      *except* `executionTimestamp` / `executionDurationMs` in the report
      (test uses `assertThat(content).contains(...)` not byte equality for the
      report; full byte equality asserted for endpoints.json + paths.json).
- [ ] `static-analysis-report.json` contains all top-level keys listed in §4.3.

## 5. Orchestrator switch

### 5.1 IterationRunner change

`orchestrator/build.gradle.kts`:
- Add `implementation(project(":graph-rag-builder"))`.
- Remove `implementation(project(":path-discovery-static"))`.

`orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java`:

Replace
```java
import io.graphrag.discovery.PathDiscoveryStatic;
...
PathDiscoveryStatic.Result discovery = PathDiscoveryStatic.discover(
        cfg.sutSource(), cfg.project(),
        "iter-" + iterIndex,
        excludePaths);
```
with
```java
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.branch.BoundaryValueConfig;
import io.graphrag.builder.staticanalysis.branch.BranchAnalyzer;
import io.graphrag.builder.staticanalysis.branch.BranchAnalysisResult;
import io.graphrag.builder.staticanalysis.domain.DomainAnalyzer;
import io.graphrag.builder.staticanalysis.domain.DomainAnalysisResult;
...
AstParseResult ast = AstParser.parse(cfg.sutSource());
DomainAnalysisResult domain = DomainAnalyzer.analyze(ast, cfg.project());
BranchAnalysisResult branch = BranchAnalyzer.analyze(
        domain,
        "iter-" + iterIndex,
        /* maxPathsPerEndpoint */ 10,
        BoundaryValueConfig.defaults(),
        excludePaths);
```

Then write:
```java
M.writerWithDefaultPrettyPrinter()
        .writeValue(layout.stage1Endpoints().toFile(), domain.endpoints());
M.writerWithDefaultPrettyPrinter()
        .writeValue(layout.stage1Paths().toFile(), branch.paths());
```

The downstream `if (discovery.endpoints().isEmpty()) ...` and
`List<String> endpointIds = discovery.endpoints().stream().map(Endpoint::id).toList();`
checks adapt trivially to `domain.endpoints()`.

### 5.2 path-discovery-static deletion (separate commit)

After the switch is committed and `OrchestratorE2eTest` GREEN:

1. `git rm -r path-discovery-static`.
2. `settings.gradle.kts`: remove `":path-discovery-static",` from the
   `include(...)` list.
3. `grep -r path-discovery-static .` — must return zero hits in build files,
   sources, and resources.
4. `./gradlew -Pagent.enabled=true check` GREEN.

### 5.3 Orchestrator-switch acceptance criteria

- [ ] `OrchestratorE2eTest.single_iteration_target_reached_terminates_and_writes_report` GREEN.
- [ ] `OrchestratorE2eTest.max_iterations_cap_stops_when_no_progress_and_target_unreached` GREEN.
- [ ] `./gradlew -Pagent.enabled=true :orchestrator:test` GREEN.
- [ ] After deletion commit: `./gradlew -Pagent.enabled=true check` GREEN
      across all modules (excluding `:path-discovery-static` which no longer
      exists).

## 6. Determinism

Inherited from T1+T2 plus new guarantees:

- `BranchAnalyzer.analyze(...)` iterates `DomainAnalysisResult.endpoints()` in
  its existing `(method, path)` sorted order.
- `SampleInputGenerator.generate(...)` iterates `MethodAnalysis.parameters()`
  in declaration order.
- `BoundaryValueConfig.defaults()` returns fresh constants; instances are
  value-equal.
- `ExploredPath.id` uses deterministic slugs — no ULIDs, no
  timestamps.
- `coverageSignature` is `"static:" + endpointId + ":" + slug` (string,
  not hashed) — deterministic and human-debuggable.

A determinism test in `BranchAnalyzerTest` runs `analyze(...)` twice and
asserts `assertThat(r1).isEqualTo(r2)`.

## 7. Error handling

| Failure mode | Behavior |
|---|---|
| `methodAnalyses` lookup miss for a controller handler | Log + `ManualReviewItem("missing_method_analysis", ...)`, emit only the happy path with no params |
| Parameter type neither numeric nor string-like | `ManualReviewItem("complex_parameter_type", ...)`, skip boundary variants for that param |
| `maxPathsPerEndpoint` cap reached | Priority order: happy > boundary. Drop excess silently (not a failure) |
| `excludeEndpointIds` contains an endpoint id | Skip that endpoint entirely — no paths, no review entries |
| `--sut-source` directory does not exist | CLI exits 2 with clear message |
| `--out` directory cannot be created | CLI exits 1 + IOException message |
| Unknown / missing required CLI flag | Exit 2 + usage |
| Per-file AST parse failure | Already handled by T1; surfaced in `static-analysis-report.json` `parsing.failures` |

No `RuntimeException` thrown from `BranchAnalyzer.analyze`,
`SampleInputGenerator.generate`, `ExploredPathBuilder.build`, or
`StaticAnalysisPathExplorer.proposeInputs`.

## 8. Build configuration

### 8.1 Module deps

| Change | Why |
|---|---|
| `orchestrator/build.gradle.kts`: add `implementation(project(":graph-rag-builder"))` | In-process call into `staticanalysis` API |
| `orchestrator/build.gradle.kts`: remove `implementation(project(":path-discovery-static"))` | Replaced by the new analyzer |
| `settings.gradle.kts`: remove `":path-discovery-static"` (deletion commit) | Module gone |

No new third-party deps. JavaParser already wired in T1+T2.

### 8.2 Build commands

```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true \
    :graph-rag-builder:test \
    :orchestrator:test
```

Full repo regression after deletion commit:
```bash
JAVA_HOME=... ./gradlew -Pagent.enabled=true check
```

## 9. Testing strategy

### 9.1 Unit tests (branch/)

- `BoundaryValueGeneratorTest`
  - `numeric_int_happy_is_one`
  - `numeric_int_variants_are_neg1_zero_maxint`
  - `string_happy_is_a`
  - `string_variants_contain_empty`
  - `isNumeric_recognises_primitive_and_boxed`
  - `isStringLike_recognises_String_and_CharSequence`
- `SampleInputGeneratorTest`
  - `endpoint_with_no_params_emits_only_happy`
  - `single_numeric_pathvar_emits_happy_plus_three_variants`
  - `single_string_querystring_emits_happy_plus_empty`
  - `request_body_endpoint_gets_empty_object_body`
  - `multi_param_only_varies_one_at_a_time`
  - `deterministic_order_under_repeat_invocation`
- `ExploredPathBuilderTest`
  - `slug_uses_handler_method_name`
  - `happy_uses_200_for_GET`
  - `happy_uses_201_for_POST`
  - `numeric_boundary_predicts_404`
  - `empty_string_boundary_predicts_400`
  - `discoveredBy_is_MANUAL`
  - `coverage_signature_matches_convention`
- `BranchAnalyzerTest`
  - `endpoint_missing_method_analysis_yields_only_happy_and_logs_queue_entry`
  - `excludePaths_skips_endpoint_entirely`
  - `maxPerEndpoint_cap_keeps_happy_first`
  - `idempotent_under_repeat_invocation`
- `StaticAnalysisPathExplorerTest`
  - `name_returns_static_ast`
  - `proposeInputs_returns_happy_first`
  - `proposeInputs_respects_budget_maxInputs`
  - `proposeInputs_deterministic`

### 9.2 Unit tests (cli/)

- `StaticAnalysisOptionsParserTest`
  - `parses_required_flags`
  - `defaults_code_version_and_max_paths`
  - `rejects_unknown_flag` (throws)
  - `rejects_missing_required_flag` (throws)
  - `parses_exclude_paths_csv`
- `StaticAnalysisCliTest` (integration against petclinic fixture)
  - `cli_writes_three_json_files`
  - `endpoints_json_loads_via_ArchiveReader`
  - `paths_json_loads_via_ArchiveReader`
  - `report_json_has_expected_top_level_keys`
  - `each_endpoint_has_at_least_one_path`
  - `excludePaths_argument_filters_output`
  - `idempotent_two_runs_same_bytes_for_endpoints_and_paths`

### 9.3 Orchestrator switch regression

- `OrchestratorE2eTest` (existing, unchanged) — both test methods stay GREEN.

### 9.4 Module-deletion regression

- After `:path-discovery-static` removal: `./gradlew check` GREEN.

### 9.5 Fixtures

Reuses existing
`graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/`:
- `Owner.java` (`@Entity`)
- `OwnerRepository.java` (`@Repository`)
- `OwnerService.java` (`@Service`)
- `OwnerRestController.java` (5 endpoints: list, getById, create, update, delete)

No new fixture needed for must-have scope.

## 10. Estimated LOC

| Layer | LOC |
|---|---|
| T3 (`branch/`) | 600–900 |
| CLI (`cli/`) | 200–350 |
| Orchestrator switch | 30–60 |
| Module deletion | net negative ~600 |
| Unit tests | 500–800 |
| Integration test (CLI) | 100–200 |
| **Total new code** | **~1,500–2,300** |

## 11. Open decisions resolved by this spec

| Decision | Resolution |
|---|---|
| Orchestrator integration mode | Both — in-process for orchestrator, CLI for standalone |
| T3 scope | Must-have only |
| `PathExplorerKind` | Reuse `MANUAL` |
| `StaticAnalysisPathExplorer` location | `staticanalysis/branch/` |
| `path-discovery-static` deletion | Separate commit, after orchestrator switch GREEN |
| Build flag | `-Pagent.enabled=true` |
| `MethodAnalysis.outgoingCalls` | Stays `List.of()` for now |
| `BranchKind.RETURN` emission | Stays unused |
| `shared-model` changes | None |
| ULID vs deterministic hash for path id | Deterministic slugs |

## 12. Subagent dispatch notes (from the T1+T2 session)

Carried forward to inform plan tasks:

- **Use `haiku` for**: records, enums, mechanical scaffolding, doc tweaks
  (BoundaryValueConfig, NamedSampleInput, ManualReviewItem,
  StaticAnalysisReport sub-records, package-info.java).
- **Use `sonnet` for**: SampleInputGenerator (parameter categorization +
  variant iteration), BranchAnalyzer (orchestration), CLI argument parser,
  StaticAnalysisCli (multi-stage pipeline), IterationRunner edit.
- **Never tell reviewer subagents to `git checkout`** — instruct
  `git diff <base>..<head>`, `git show`, `Read` only.
- **Build flag is mandatory**: every gradle command must include
  `-Pagent.enabled=true`.
- **`Map.copyOf()` discards insertion order** — use
  `Collections.unmodifiableMap(new LinkedHashMap<>(input))` for ordered maps
  in records.
- **Cumulative test count check** at the very end: full
  `:graph-rag-builder:test` + `:orchestrator:test` GREEN; no regressions in
  existing tests.

## 13. Acceptance criteria for the session

- [ ] `staticanalysis/branch/` package builds + tests GREEN.
- [ ] `staticanalysis/cli/` package builds + `StaticAnalysisCliTest` invokes
      the CLI on the petclinic fixture and asserts the 3 output JSON files
      exist and parse correctly.
- [ ] Each endpoint in `paths.json` has ≥1 `ExploredPath` (happy path).
- [ ] `./gradlew -Pagent.enabled=true :graph-rag-builder:test` GREEN, no
      regressions.
- [ ] `IterationRunner` uses `BranchAnalyzer` instead of
      `PathDiscoveryStatic.discover`. `OrchestratorE2eTest` GREEN.
- [ ] `path-discovery-static/` directory removed; `settings.gradle.kts` no
      longer includes it; `./gradlew -Pagent.enabled=true check` GREEN.
- [ ] No real petclinic mvn build attempted (deferred per handoff §6).
