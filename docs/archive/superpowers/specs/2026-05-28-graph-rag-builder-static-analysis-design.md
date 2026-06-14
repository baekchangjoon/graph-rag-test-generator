# graph-rag-builder Static Analysis — T1+T2 Design

> Spec for the AST parsing (T1) and Domain analysis + Endpoint extraction (T2)
> portions of `graph-rag-builder-static-analysis-work-order.md` (rev.1).
>
> Date: 2026-05-28
> Branch: feat/t6-orchestrator (scope expands beyond T6 — see §1)
> Author: assistant (Claude Opus 4.7)

## 1. Scope

This spec covers **T1 and T2 only**. T3 (branch analysis + `SampleInput`
generation), CLI, file output, `StaticAnalysisPathExplorer` SPI implementation,
and orchestrator integration are explicitly **out of scope** and will be picked
up in a follow-up session.

Two reasons for the cut:

- Full work-order scope is 3,400–5,100 LOC (~4–6 weeks). A single session can
  realistically deliver T1+T2 (~2,000 LOC) at quality bar.
- T2's output (`DomainAnalysisResult.endpoints`) is *consumable* on its own —
  the data structure is complete enough that T3 can be designed against it
  next session without re-litigating T1/T2 contracts.

### Relationship to existing `path-discovery-static` module

`path-discovery-static/` is a working but narrow controller scanner that the
T6 orchestrator currently calls in-process (`IterationRunner.runOne` →
`PathDiscoveryStatic.discover`). It overlaps with this spec's T1/T2 in
intent but lacks `SymbolSolver`, `ClassRole`, `MethodAnalysis`, `Branch`,
and `CallGraph` — the structures T3 will need.

**Decision (user-confirmed)**: keep `path-discovery-static` running as the
orchestrator's Stage 1 *this session*. The new `staticanalysis` package
becomes the primary analyzer. `path-discovery-static` will be removed and
the orchestrator will switch to the new API in the same session that lands
T3 + CLI.

### Out of scope (deferred / not done by this spec)

- T3 — `staticanalysis/branch/` package, deterministic branch → SampleInput,
  `StaticAnalysisPathExplorer` SPI impl, `ExploredPath` generation.
- CLI — `staticanalysis/cli/StaticAnalysisCli`, `endpoints.json` /
  `paths.json` / `static-analysis-report.json` file output.
- `IterationRunner` integration (orchestrator continues to call
  `PathDiscoveryStatic.discover` until T3+CLI session).
- `path-discovery-static` module deletion.
- `shared-model` changes. Work order recommends reusing
  `PathExplorerKind.MANUAL`; no enum value added.
- Default-build (`-Pagent.enabled=false`) `JdbcAgentBaggageBridge`
  compile error in `ArchiveShutdownWriter.java` — pre-existing, tracked
  separately.

## 2. Package layout

```
graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/
├── ast/
│   ├── AstParser.java
│   ├── AstParseResult.java
│   ├── ParsedFile.java
│   ├── ParseFailure.java
│   ├── SymbolResolverFactory.java
│   └── package-info.java
├── domain/
│   ├── DomainAnalyzer.java
│   ├── DomainAnalysisResult.java
│   ├── ClassRole.java
│   ├── ClassRoleClassifier.java
│   ├── EndpointExtractor.java
│   ├── BranchExtractor.java
│   ├── CallGraphBuilder.java
│   ├── MethodAnalysis.java
│   ├── Branch.java
│   ├── BranchKind.java
│   ├── CallGraph.java
│   ├── MethodCall.java
│   ├── Parameter.java
│   ├── ReturnType.java
│   └── package-info.java
└── (branch/, cli/ — not created this session)
```

Test sources mirror the layout under `src/test/java/.../staticanalysis/{ast,domain}`.

## 3. T1 — AST parsing

### 3.1 Contract

```java
public final class AstParser {
    public static AstParseResult parse(Path sourceDir) throws IOException;
    public static AstParseResult parse(Path sourceDir,
                                       List<Path> classpathJars) throws IOException;
}
```

- `Files.walk(sourceDir)` filters `*.java` regular files.
- Stream is collected and **sorted by path string** before parsing for
  deterministic output ordering.
- Each file is parsed with `JavaParser` configured to use
  `SymbolResolverFactory.create(sourceDir, classpathJars)`. A `ParseProblemException`
  (or any `Throwable` during a single-file parse) is captured as a
  `ParseFailure(sourcePath, message)` and the loop continues.
- `package-info.java` files are tolerated (parsed but not required to declare a class).

### 3.2 Records

```java
record AstParseResult(List<ParsedFile> parsedFiles,
                      List<ParseFailure> failures) {}

record ParsedFile(Path sourcePath, String packageName, String className,
                  CompilationUnit cu) {}

record ParseFailure(Path sourcePath, String message) {}
```

`className` = top-level type's simple name. Nested/inner classes are not
flattened into separate `ParsedFile`s — `DomainAnalyzer` traverses the
`CompilationUnit` directly when it needs them.

### 3.3 SymbolResolverFactory

```java
final class SymbolResolverFactory {
    static JavaSymbolSolver create(Path sourceRoot, List<Path> jars);
}
```

Returns a `JavaSymbolSolver(CombinedTypeSolver(
ReflectionTypeSolver(false),
JavaParserTypeSolver(sourceRoot),
[JarTypeSolver(jar) for each jar in jars]
))`.

`classpathJars` is reserved for future use (Spring annotation type
resolution) — the T1+T2 deliverable accepts the parameter but petclinic
analysis does not require it.

### 3.4 Acceptance criteria (T1)

- petclinic `src/main/java` input → ≥80 files parsed; 0 failures.
- 2× run on same input → identical `parsedFiles` ordering.
- Synthetic test fixture with one broken `.java` file → file appears in
  `failures`; other files in `parsedFiles`.
- `ParsedFile.className` equals the top-level class simple name.

## 4. T2 — Domain analysis

### 4.1 Contract

```java
public final class DomainAnalyzer {
    public static DomainAnalysisResult analyze(AstParseResult ast, String project);
}
```

### 4.2 Result record

```java
record DomainAnalysisResult(
    List<Endpoint> endpoints,                       // shared-model.Endpoint
    Map<String, ClassRole> classRoles,              // classFqn → role
    Map<String, MethodAnalysis> methodAnalyses,     // "classFqn#methodName" → analysis
    CallGraph callGraph
) {}
```

- `endpoints` sorted by `(path asc, method asc)` — deterministic.
- `classRoles` and `methodAnalyses` are `LinkedHashMap` preserving insertion order
  (insertion order = parsedFiles order = path sort order).

### 4.3 ClassRole classification

```
@RestController, @Controller                              → CONTROLLER
@Service                                                   → SERVICE
@Repository, @Mapper, extends JpaRepository/CrudRepository → REPOSITORY
@Entity, @Embeddable, @MappedSuperclass                    → DOMAIN
otherwise                                                  → OTHER
```

Annotation match is by **simple name** (no FQN resolution required) — same
approach as `path-discovery-static`'s `MappingAnnotation`. `extends`/
`implements` checks scan the `extendedTypes`/`implementedTypes` simple
names of `ClassOrInterfaceDeclaration`.

### 4.4 Endpoint extraction

Inputs: every `ClassOrInterfaceDeclaration` classified as `CONTROLLER`.

Algorithm:
1. Read class-level `@RequestMapping` for path prefix (use the first `path`
   or `value` array element). Normalize to leading `/`, strip trailing `/`.
2. For each method, look for the first mapping annotation in this order:
   `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping/
   @RequestMapping(method=...)`. Skip method otherwise.
3. Compose `fullPath = classBase + methodPath`. Normalize.
4. Compose `Endpoint`:
   - `id = "{METHOD}:{fullPath}"`
   - `method = HttpMethod.<name>`
   - `path = fullPath`
   - `project = caller-provided project`
   - `handlerClass = classFqn`
   - `handlerMethod = method simple name` (T1+T2 does not include signature
     hash — sufficient for `id` uniqueness in petclinic; revisit if collisions arise)
   - `authRequired = true` iff class or method carries
     `@PreAuthorize`/`@Secured`/`@RolesAllowed`
   - `requiredRoles` — extracted per work-order §7.4.3 patterns:
     - `@PreAuthorize("hasRole('ADMIN')")` → `["ADMIN"]`
     - `@PreAuthorize("hasAnyRole('A','B')")` → `["A", "B"]`
     - `@PreAuthorize("isAuthenticated()")` → `[]`
     - `@Secured({"ROLE_ADMIN","ROLE_USER"})` → `["ADMIN","USER"]` (strip `ROLE_` prefix)
     - `@RolesAllowed({"USER"})` → `["USER"]`
     - Unrecognized SpEL → `[]` + log to `manualReviewQueue` (the queue is
       a `DomainAnalysisResult` field added in T3; for this session we just log)

### 4.5 MethodAnalysis (per method, all endpoint handlers + all controller/service/repository methods)

```java
record MethodAnalysis(
    String classFqn,
    String methodName,
    List<Parameter> parameters,           // ordered as declared
    List<Branch> branches,                 // ordered by line number
    List<MethodCall> outgoingCalls,        // ordered by line number
    ReturnType returnType
) {}

record Parameter(String name, String type, List<String> annotations);
record ReturnType(String type, boolean isVoid);
```

Source of methods analyzed: all methods declared in `CONTROLLER`, `SERVICE`,
`REPOSITORY` classes (DOMAIN/OTHER skipped — they're data, not behavior).

### 4.6 Branch extraction

`BranchExtractor.extract(MethodDeclaration m, String classFqn) → List<Branch>`:

| AST node                | `BranchKind` | `condition`                  |
|-------------------------|--------------|------------------------------|
| `IfStmt`                | `IF`         | `getCondition().toString()`  |
| `SwitchStmt`/`SwitchExpr` | `SWITCH`    | `getSelector().toString()`   |
| `ConditionalExpr` (`?:`) | `TERNARY`    | `getCondition().toString()`  |
| `ThrowStmt`             | `THROW`      | enclosing if/return text or `""` |
| `ReturnStmt` inside any block other than method body | `RETURN` | enclosing condition or `""` |

`Branch.id = "{classFqn}#{methodName}:line{N}"` (1-based line of the AST
node's `Range.begin.line`). `referencedVariables` = identifiers used in the
condition (extracted by walking `NameExpr` nodes; deduplicated, sorted).

### 4.7 CallGraph

```java
record MethodCall(String calleeClassFqn,    // null if unresolved
                  String calleeMethodName,
                  int line,
                  boolean resolved);

final class CallGraph {
    Map<String, List<String>> edges();      // "classFqn#methodName" → calleeKeys
}
```

`CallGraphBuilder` walks `MethodCallExpr` in each analyzed method. It
attempts `methodCall.resolve()` (uses the SymbolResolver from T1). On
success, records `(calleeFqn, calleeMethodName, line, true)`. On
`UnsolvedSymbolException` (or any resolve exception), falls back to
recording the call expression's `getNameAsString()` as `calleeMethodName`
with `calleeClassFqn = null` and `resolved = false` — useful for T3 to
flag manual-review candidates.

External library calls (callee class not present in any `ParsedFile`) are
recorded as `resolved=true` (if resolution succeeded) but **excluded from
`CallGraph.edges()`** — we only graph in-project edges.

### 4.8 Acceptance criteria (T2)

- petclinic input → `endpoints` contains at least all of:
  `GET:/owners`, `GET:/owners/{ownerId}`, `POST:/owners`, `PUT:/owners/{ownerId}`,
  `GET:/vets.html` *(or `/vets`)* — total ≥ 10 endpoints.
- Each `endpoint.id` matches `^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS):/.+$`.
- `OwnerController` (or `OwnerRestController` depending on petclinic flavor)
  → `ClassRole.CONTROLLER`.
- `Owner` (`@Entity`) → `DOMAIN`.
- `OwnerRepository` (extends `JpaRepository` or `@Repository`) → `REPOSITORY`.
- Each endpoint handler has a corresponding entry in `methodAnalyses`.
- `BranchExtractor` unit test: nested `if`/`switch`/ternary all surfaced as
  separate `Branch` entries with correct line numbers.
- `@PreAuthorize("hasRole('ADMIN')")` → `requiredRoles=["ADMIN"]`.
- 2× run on same input → identical `endpoints` ordering and `methodAnalyses`
  iteration order.

## 5. Build configuration

### 5.1 Dependency

Add to `graph-rag-builder/build.gradle.kts` `dependencies` block:

```kotlin
implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.10")
```

Same version used by `path-discovery-static` — verified via that module's
existing dependencies. No new test dependency required (JUnit5 +
AssertJ + Mockito already present).

### 5.2 Build flag

This session uses `-Pagent.enabled=true` for all builds (Maven Local has
the `agent-core` jar). The default-build (`agent.enabled=false`) compile
error in `ArchiveShutdownWriter.java` referencing `JdbcAgentBaggageBridge`
is **pre-existing and out of scope** — tracked for a separate fix.

### 5.3 Test execution

```bash
JAVA_HOME=<corretto-17> ./gradlew -Pagent.enabled=true \
  :graph-rag-builder:test \
  --tests "io.graphrag.builder.staticanalysis.*"
```

Existing `graph-rag-builder` tests (JdbcAgentBaggageBridgeTest etc.) must
remain GREEN; new tests are additive.

## 6. Determinism

Every public output from this package is deterministic:

- `AstParser.parse` sorts file paths before processing.
- `DomainAnalyzer.analyze` returns:
  - `endpoints` sorted by `(method, path)`.
  - `classRoles`, `methodAnalyses` as `LinkedHashMap` populated in
    `parsedFiles` order.
  - `MethodAnalysis.branches`, `.outgoingCalls` ordered by line.
  - `Branch.referencedVariables` deduplicated, sorted.

A determinism test in `DomainAnalyzerTest` runs `analyze` twice on the
same `AstParseResult` and `assertThat(r1).isEqualTo(r2)` (records have
auto-generated `equals`).

## 7. Error handling

| Failure mode                                | Behavior                                           |
|---------------------------------------------|----------------------------------------------------|
| Single `.java` file unparseable             | Captured in `AstParseResult.failures`, others continue. |
| `SymbolSolver` cannot resolve a call        | `MethodCall.resolved=false`, `calleeClassFqn=null`. |
| Unknown annotation on class/method          | `ClassRole.OTHER`, endpoint not extracted.         |
| Unknown SpEL in `@PreAuthorize`             | `requiredRoles=[]`, condition raw string logged.   |
| `@RequestMapping` without `method=` attr    | Skipped (no endpoint extracted).                   |
| Missing `path`/`value` on mapping annotation| Empty path (handled in normalization).              |
| Cyclic call graph                           | `CallGraph.edges()` records edges; traversal users use visited-set. |

No `RuntimeException` is thrown from `parse` or `analyze` for any of these
modes. The methods declare `throws IOException` for filesystem failures only.

## 8. Testing strategy

### 8.1 Unit tests (T1)

- `AstParserTest`:
  - `parses_empty_dir_yields_empty_result`
  - `parses_single_valid_file`
  - `broken_file_isolated_in_failures`
  - `path_sort_is_deterministic`
  - `package_info_files_tolerated`
- `SymbolResolverFactoryTest`:
  - `resolves_in_source_method_call`
  - `unresolvable_call_does_not_throw`

### 8.2 Unit tests (T2)

- `ClassRoleClassifierTest` — table-driven per role
- `EndpointExtractorTest`:
  - `class_level_request_mapping_prefix_joined`
  - `each_shorthand_annotation_maps_to_method`
  - `request_mapping_without_method_is_skipped`
  - `pre_authorize_hasrole_to_required_roles`
  - `secured_strips_role_prefix`
  - `path_variable_in_path_preserved`
- `BranchExtractorTest`:
  - `if_then_else_two_branches`
  - `nested_if_surfaced_separately`
  - `switch_each_case_one_branch`
  - `ternary_extracted_with_line`
  - `throw_statement_with_enclosing_condition`
  - `referenced_variables_deduplicated_sorted`
- `CallGraphBuilderTest`:
  - `resolved_in_project_call_recorded`
  - `unresolved_call_recorded_as_unresolved`
  - `external_call_excluded_from_edges`
  - `cycle_does_not_recurse_in_analyzer`

### 8.3 Integration test

`DomainAnalyzerPetclinicTest` — fixture under
`graph-rag-builder/src/test/resources/staticanalysis/petclinic-fixture/`
containing a mini-petclinic with:
- `OwnerRestController` (CRUD endpoints, `@RestController`, class-level
  `@RequestMapping("/owners")`)
- `OwnerService` (`@Service`)
- `OwnerRepository extends JpaRepository<Owner, Integer>` (REPOSITORY)
- `Owner` (`@Entity`, DOMAIN)
- One handler with `@PreAuthorize("hasRole('ADMIN')")`
- One handler with a nested `if` and a `switch`

Asserts the acceptance criteria from §4.8 directly against the fixture.
We *do not* clone real petclinic in this test — fixture is committed for
reproducibility.

## 9. Open decisions resolved by this spec

| Decision                              | Resolution                                      |
|---------------------------------------|------------------------------------------------|
| Module strategy (path-discovery-static) | Replace strategy; deferred to next session.   |
| Session scope                          | T1 + T2 only.                                  |
| Approach                               | Fresh code following work-order spec exactly.  |
| `PathExplorerKind.STATIC_AST`          | Not added — work-order recommends MANUAL reuse, applied in T3. |
| ULID vs deterministic hash for path id | Deferred (T3 concern).                         |
| Symbol resolution fallback             | Record as `MethodCall.resolved=false`.         |
| Build flag                             | `-Pagent.enabled=true` for all builds.         |

## 10. Estimated LOC

| Layer        | LOC      |
|--------------|----------|
| T1 (ast/)    | 350–500  |
| T2 (domain/) | 1,200–1,500 |
| Unit tests   | 600–900  |
| Integration test + fixture | 200–300 |
| **Total**    | **~2,400–3,200** |

## 11. Next session checklist (T3 + CLI)

For continuity, the next session will:

1. Add `branch/` package: `BranchAnalyzer`, `DeterministicBranchDetector`,
   `ConditionParser`, `SampleInputGenerator`, `BoundaryValueGenerator`,
   `BoundaryValueConfig`, `ExploredPathBuilder`, `PathConstraintBuilder`,
   `StaticAnalysisPathExplorer`.
2. Add `cli/` package: `StaticAnalysisCli`, `StaticAnalysisOptions`.
3. Emit `endpoints.json` + `paths.json` + `static-analysis-report.json`.
4. Switch `orchestrator/IterationRunner` from `PathDiscoveryStatic.discover`
   to the new graph-rag-builder CLI (subprocess) or in-process API.
5. Delete the `path-discovery-static` module.
6. Verify orchestrator E2E still passes.
