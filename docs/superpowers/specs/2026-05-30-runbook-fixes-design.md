# Runbook Follow-ups #1 + #3 — Design Spec

> Date: 2026-05-30
> Inputs: `docs/orchestrator-e2e-petclinic.md` §"Known limitations" items #1 and #3
> Follows PR #13 (real-petclinic E2E baseline)

## 0. Summary

Make the cumulative coverage actually grow across iterations by closing the two contained gaps the PR #13 reference run surfaced:

- **#1** Synthesized RestAssured tests don't reach a live SUT — `APP_BASE_URI` is unset and petclinic is already down by Stage 5 time. Fix in `scripts/petclinic-stage5.sh`: launch a fresh petclinic instance, export `APP_BASE_URI`, stop it on EXIT.
- **#3** `@PathVariable("ownerId") Owner owner` Spring pattern is unmodeled — the static analyzer keys `pathParams` by the Java parameter name (`owner`) not the annotation value (`ownerId`), so the orchestrator's defensive filter quarantines those endpoints. Fix in `Parameter` + `DomainAnalyzer` + `SampleInputGenerator`: read the annotation's primary value and prefer it as the key.

Together these two should:
- Take generated tests from "error at baseURI null" to "actually hit petclinic".
- Take quarantined endpoints from 11 down toward 0 (depending on how many of petclinic's HTML controllers use the rename pattern — at least 8 paths in the reference run miss `ownerId` / `petId`).
- Move branch coverage above the petclinic-own-tests floor of 0.561.

Out of scope: the boundary-value-only path explorer redesign (Known limitations #2), `-Pagent.enabled=true` cleanup (#4), `jdbc-intercept-agent` access (#5).

## 1. Goals & non-goals

### 1.1 Goals

1. `petclinic-stage5.sh` boots petclinic in background, waits for `/actuator/health`, runs mvn with `APP_BASE_URI` exported, then stops the SUT — regardless of mvn outcome.
2. `Parameter` record carries each annotation's primary value (`@PathVariable("ownerId")` → `{"PathVariable": "ownerId"}`).
3. `DomainAnalyzer` populates `annotationValues` from JavaParser, supporting all three Spring forms: single-member (`@PathVariable("x")`), normal with `value` pair (`@PathVariable(value = "x")`), normal with `name` pair (`@PathVariable(name = "x")`), and marker (`@PathVariable`).
4. `SampleInputGenerator` keys pathParams / queryParams / headerParams by `annotationValues.get(annotationName)` first, falling back to the Java parameter name.
5. Reference re-run shows: quarantined-at-Stage-2 endpoints drop substantially; iter 2 / 3 produce non-zero `newlyCovered` (coverage actually grows); the loop reaches its 0.70 target or runs to the iteration cap with monotone-increasing branch coverage.

### 1.2 Non-goals

- Re-architecting `SampleInputGenerator` to handle non-numeric/non-string types (e.g., custom domain classes like `Owner`). Spring's auto-conversion magic stays unmodeled — but the path key is now correct.
- Adding `@PathVariable("required = false")` semantics.
- Restarting the SUT mid-iteration if mvn crashes.
- Wiring SUT-launch into the Stage 5 wrapper's unit tests (too heavy; live-only).

## 2. Component changes

### 2.1 `scripts/petclinic-stage5.sh` (no-test bash)

**New env vars (all optional, sane defaults):**
- `SUT_JAR` — default `$PETCLINIC_DIR/target/spring-petclinic-4.0.0-SNAPSHOT.jar`
- `SUT_PORT` — default `8084`
- `SUT_HEALTH_URL` — default `http://localhost:$SUT_PORT/actuator/health`
- `SUT_HEALTH_TIMEOUT_SECS` — default `60`

**New section** (inserted after the existing rest-assured pom inject, before the `(cd "$PETCLINIC_DIR" && mvn ...)` block):

```bash
SUT_JAR="${SUT_JAR:-$PETCLINIC_DIR/target/spring-petclinic-4.0.0-SNAPSHOT.jar}"
SUT_PORT="${SUT_PORT:-8084}"
SUT_HEALTH_URL="${SUT_HEALTH_URL:-http://localhost:$SUT_PORT/actuator/health}"
SUT_HEALTH_TIMEOUT_SECS="${SUT_HEALTH_TIMEOUT_SECS:-60}"
SUT_PID=""
SUT_LOG="$PETCLINIC_DIR/target/stage5-sut.log"

launch_sut() {
  if [[ ! -f "$SUT_JAR" ]]; then
    echo "error: SUT jar not found at $SUT_JAR" >&2
    return 6
  fi
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
      tail -20 "$SUT_LOG" >&2
      return 7
    fi
    sleep 1
  done
  echo "error: SUT did not become healthy within ${SUT_HEALTH_TIMEOUT_SECS}s" >&2
  tail -20 "$SUT_LOG" >&2
  kill "$SUT_PID" 2>/dev/null || true
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

**`cleanup` trap** extended to also call `stop_sut`:
```bash
cleanup() {
  stop_sut
  rm -rf "$INJECTED_ROOT"
  if [[ -f "$POM_BACKUP" ]]; then
    mv "$POM_BACKUP" "$PETCLINIC_DIR/pom.xml"
  fi
}
trap cleanup EXIT
```

**`launch_sut`** called before the mvn block. **mvn invocation** gets `APP_BASE_URI` prefix:
```bash
launch_sut

(
  cd "$PETCLINIC_DIR"
  APP_BASE_URI="http://localhost:$SUT_PORT" \
    mvn -q -DskipITs -Dspring-javaformat.skip=true -Dmaven.test.failure.ignore=true \
        test jacoco:report
)
```

**New exit codes**: 6 (jar missing), 7 (SUT died early), 8 (SUT health timeout). The existing 2/3/4/5 keep their meaning.

### 2.2 `Parameter` record (+1 field)

```java
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

    // Backward-compatible constructor for sites that don't carry annotation values.
    public Parameter(String name, String type, List<String> annotations) {
        this(name, type, annotations, Map.of());
    }
}
```

The compact constructor + backward-compatible secondary constructor keeps existing test fixtures working without sweeping changes. New code (`DomainAnalyzer`) uses the four-arg constructor.

### 2.3 `DomainAnalyzer.buildMethodAnalysis` — annotation-value extraction

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
    // ... rest unchanged
}

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
    return null;  // marker annotation or no recognized value pair
}

private static String unquoteStringLiteral(String raw) {
    if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        return raw.substring(1, raw.length() - 1);
    }
    return raw;
}
```

`MemberValuePair` is imported from `com.github.javaparser.ast.expr`.

### 2.4 `SampleInputGenerator` — annotation-value key lookup

Replace the four sites where `c.param.name()` is the key in `happy()` and `boundary()`:

```java
case PATH   -> pathParams.put(paramKey(c.param, "PathVariable"),
                              BoundaryValueGenerator.happy(c.param.type(), cfg));
case QUERY  -> queryParams.put(paramKey(c.param, "RequestParam"),
                               BoundaryValueGenerator.happy(c.param.type(), cfg));
case HEADER -> headers.put(paramKey(c.param, "RequestHeader"),
                           BoundaryValueGenerator.happy(c.param.type(), cfg));
```

(And the same pattern in `boundary()`.)

New helper:

```java
private static String paramKey(Parameter p, String annotationName) {
    String v = p.annotationValues().get(annotationName);
    return v != null && !v.isEmpty() ? v : p.name();
}
```

## 3. Acceptance criteria

- [ ] `:graph-rag-builder:test` GREEN; new `DomainAnalyzer` extraction test covers four annotation forms (single-member, normal/value, normal/name, marker).
- [ ] `SampleInputGenerator` tests still GREEN after the key-selection change; existing tests that assert pathParam-key behavior get a new test variant exercising the `@PathVariable("renamed")` case.
- [ ] `:orchestrator:test` GREEN; the existing `PetclinicStage5ScriptTest` happy-path stays GREEN (the wrapper's new SUT block is only exercised in live runs, but the `mvn` stub now sees `APP_BASE_URI` set in env — assert that).
- [ ] `./gradlew -Pagent.enabled=true check` GREEN.
- [ ] Live re-run against real petclinic (5-iter, target 0.70) shows: branch coverage > 0.561 (the prior floor) on at least one iteration; iter 2 / 3's `newlyCovered` non-zero; runbook §"Results from the reference run" updated with new numbers.
- [ ] Stage 1 quarantine count drops (i.e., `@PathVariable("ownerId")` paths now go through to Stages 3-6).

## 4. Out of scope / explicit non-goals (re-stated)

- Domain object types (`Owner`, `Pet`, `Visit`) staying unmodeled by `BoundaryValueGenerator`. We're fixing the KEY, not the VALUE.
- Adding new wrapper unit tests that simulate SUT launch — too heavy. Live run is the test.
- Refactoring `BranchAnalyzer` or `ExploredPathBuilder` to emit more variants per endpoint.
- Updating the orchestrator's defensive placeholder filter — it stays as the last line of defense; what changes is that fewer paths trip it.

## 5. Decision log

| # | Decision | Rationale | Alternatives rejected |
|---|---|---|---|
| D1 | Wrapper launches SUT itself, not orchestrator | The orchestrator's Stage-3 scout-launcher needs to terminate the SUT (to flush the archive). Stage 5 needs a fresh SUT. Decoupling lifecycles keeps each stage's responsibility crisp. | Reuse scout-launcher binary for Stage-5 SUT (drags too much capture machinery); use @SpringBootTest in generated tests (couples tests to petclinic's Spring Boot Application class) |
| D2 | SUT defaults in wrapper match `template.yml` literals | Avoids YAML parsing in bash; advanced users override via env vars | Parse template.yml in bash (fragile); pass full launch command via env (verbose) |
| D3 | `Parameter` gets backward-compatible 3-arg constructor | Existing tests construct `Parameter` directly; sweeping changes would be noise | Make the new field non-nullable everywhere (forces a sweep) |
| D4 | Annotation value extracted as `Map<String, String>` keyed by annotation simple name | Spring uses the same `value`/`name` aliases everywhere; one helper handles all three (PathVariable / RequestParam / RequestHeader). Multiple annotation values per param are rare in practice. | Per-annotation typed accessors on Parameter (more types, more code); a single string `primaryAnnotationValue` (loses which annotation owns it) |
| D5 | New exit codes 6/7/8 for SUT launch failures | Distinct from existing 2-5 so live-run troubleshooting can map exit to phase quickly | Reuse exit 1 for everything (loses diagnostic value) |

## 6. References

- `docs/orchestrator-e2e-petclinic.md` — runbook, especially §Known limitations.
- `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/SampleInputGenerator.java` — current source-of-keys logic.
- `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/{Parameter,DomainAnalyzer}.java` — types and their loader.
- `scripts/petclinic-stage5.sh` — current wrapper from PR #13.
- `test-generator/src/main/java/io/graphrag/generator/core/TestSynthesizer.java` — verified: already reads `APP_BASE_URI` from env. No change needed.
