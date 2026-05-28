# Orchestrator E2E — spring-petclinic

End-to-end runbook for driving the merged 6-stage orchestrator against the real
[spring-petclinic](https://github.com/spring-projects/spring-petclinic) SUT with
Postgres in docker, JaCoCo coverage measured against petclinic's production code,
and the coverage-feedback loop iterating up to 5 times.

## What this run proves

The reference run below demonstrates that:
- All 6 stages compose end-to-end against a real Spring SUT (no fakes).
- The defensive filters added in this session (`output.archive-dir` rewrite,
  Stage-5 args passthrough, unbound-path-template quarantine, per-path-id
  archive aggregation, env-var resolution, JaCoCo DOCTYPE strip) keep the
  loop running where each previously aborted.
- Stage 6's `two_iterations_no_progress` heuristic correctly terminates the
  loop when the explorer stops producing new coverage signal.
- 4 RestAssured tests compile inside petclinic's source tree and execute
  (4 / 64 surefire runs; tests error at runtime — see "Known limitations").
- `final-report.md` lands with actual numbers and a missing-branches list.

## Prereqs (one-time)

```bash
# 1) Clone petclinic — pinned to a known-good commit for reproducibility.
git clone https://github.com/spring-projects/spring-petclinic.git \
  ~/github_spring-petclinic/spring-petclinic
git -C ~/github_spring-petclinic/spring-petclinic checkout e4bebf2

# 2) Build petclinic SUT jar.
cd ~/github_spring-petclinic/spring-petclinic && mvn -DskipTests package

# 3) Clone the private jdbc-intercept-agent + publish to Maven Local.
git clone https://github.com/baekchangjoon/jdbc-intercept-agent.git \
  ~/github_jdbc-intercept-agent/jdbc-intercept-agent
cd ~/github_jdbc-intercept-agent/jdbc-intercept-agent && ./gradlew publishToMavenLocal

# 4) Publish graph-rag bridges to Maven Local.
cd ~/graph-rag/graph-rag
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true \
    :shared-model:publishToMavenLocal \
    :graph-rag-builder:publishToMavenLocal

# 5) Build orchestrator + scout-launcher + test-generator binaries.
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true \
    :orchestrator:installDist \
    :scout-launcher:installDist \
    :test-generator:installDist
```

`PETCLINIC_DIR` and `TEST_PACKAGE` env vars in `scripts/petclinic-stage5.sh`
default to the above paths; override them if your clone lives elsewhere.

## Per-run setup

```bash
# Bring Postgres up. (Redis + Kafka were dropped from samples/scout/petclinic/
# docker-compose.yml because petclinic doesn't need them and bitnami/kafka:3.7
# was deprecated upstream in 2026.) Containers stay up across iterations —
# template.yml sets teardown-on-exit: false, saving ~30 s × N iters.
docker compose -f samples/scout/petclinic/docker-compose.yml up -d
```

## Run

```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./orchestrator/build/install/orchestrator/bin/orchestrator \
    --sut-source            "$HOME/github_spring-petclinic/spring-petclinic/src/main/java" \
    --project               petclinic \
    --test-package          com.example.petclinic.tests \
    --scout-config-template samples/scout/petclinic/template.yml \
    --scout-base-url        http://localhost:8084 \
    --out                   /tmp/graph-rag-petclinic-e2e \
    --coverage-target       0.70 \
    --max-iterations        5 \
    --scout-launcher-bin    ./scout-launcher/build/install/scout-launcher/bin/scout-launcher \
    --test-generator-bin    ./test-generator/build/install/test-generator/bin/test-generator \
    --user-test-command     ./scripts/petclinic-stage5.sh
```

Wall-clock: ~3-5 min for 3 iters (5 with no early-terminate). Each iter does
one mvn test against petclinic plus scout-launcher's per-request capture.

## Results from the reference run (date: 2026-05-30, commit `e4bebf2` of petclinic)

| iter | branch | line  | newly_covered | still_missing | terminate | reason |
|-----:|------:|------:|--------------:|--------------:|:----------|:-------|
| 1    | 0.561 | 0.688 | 0             | 29            | no        | —      |
| 2    | 0.561 | 0.688 | 0             | 29            | no        | —      |
| 3    | 0.561 | 0.688 | 0             | 29            | yes       | two_iterations_no_progress |
| 4    | —     | —     | —             | —             | —         | (loop terminated at iter-3) |
| 5    | —     | —     | —             | —             | —         | (loop terminated at iter-3) |

**Stage 3 quarantine summary** (iter-1; iters 2-3 are identical because the
explorer is boundary-value-only and the same paths get re-emitted):
- 11 paths discovered by Stage 1 but quarantined at Stage 2 for unbound
  URL placeholders (Spring's `@PathVariable("ownerId") Owner owner`
  pattern, where the Java param name diverges from the URL placeholder).
- 26 of 53 captured steps quarantined by scout-launcher's strict-mode
  (expected-status vs actual-status mismatch — the static analyzer
  predicts 404 for many invalid inputs that actually return 500 because
  petclinic doesn't have an `@ExceptionHandler` mapping for them yet).

**Stage 4 output** (per iter): four RestAssured tests synthesized —
`ResourceGetTest`, `TypesGetTest`, `FindGetTest`, `NewGetTest` (one per
captured-but-non-quarantined GET endpoint with a happy-path archive).

**Coverage attribution**: branch=0.561 reflects petclinic's own ~60 surefire
tests. The 4 generated tests reach Surefire with the post-fix wiring (#1 closed:
the wrapper now launches petclinic in background and exports `APP_BASE_URI`),
but each one errors at runtime with a `NullPointerException` deep inside
RestAssured 5.4.0's Groovy closure machinery — see "Known limitations" §1 for
the residual ticket. The 60 petclinic-own tests still pass, so the loop has a
real coverage signal to feed into Stage 6's termination decision.

**Top missing branches** (29 total; full list in `final-report.md`):
- security/JwtAuthenticationFilter:38, :48, :51 — auth filter paths (10 missed branches)
- security/AuthController:74, :78 — login/validate (6 missed)
- security/JwtUtil:67 — token validation (4 missed)
- owner/{Owner,Pet,Pet}RestController — REST POST/PUT paths the strict-mode
  quarantined out due to predicted 201 / actual 500 (16 missed across files)

## Teardown

```bash
docker compose -f samples/scout/petclinic/docker-compose.yml down
```

The Stage-5 wrapper's EXIT trap removes its injected tests and restores
petclinic's `pom.xml` automatically, so the petclinic clone is left untouched
between runs.

## Known limitations (priority-ordered for follow-up work)

1. **Generated RestAssured tests error at runtime.** PR #13's original
   issue ("`baseURI` cannot be null, SUT not running") is now resolved:
   the Stage 5 wrapper launches petclinic in background, waits for
   `/actuator/health`, exports `APP_BASE_URI=http://localhost:$SUT_PORT`,
   and stops the SUT on EXIT. But on JDK 17 + RestAssured 5.4.0, each
   synthesized `given().when().get(...)` NPEs deep in Groovy's
   `ClosureMetaClass.invokeOnDelegationObject` before the HTTP request
   is sent. `--add-opens` on Surefire's `argLine` doesn't fix it — the
   stack trace shows `java.lang.Class.isAssignableFrom(null)`, which is
   a real null reference inside RestAssured's request-building closure,
   not a module-access denial. Mitigations to try in a follow-up:
   bump test-generator to RestAssured 5.5.x (drops some Groovy paths);
   or have `TestSynthesizer` emit a `@SpringBootTest`-style template
   that uses `TestRestTemplate` / `WebTestClient` instead of RestAssured.
2. **Static path explorer is boundary-value-only.** Later iters regenerate
   the same paths unless Stage 6's `excludePaths` prunes them. The wrapper
   accumulates `iter-*/stage4-tests` so coverage is monotone, but with no
   new branches explored the loop hits `two_iterations_no_progress` quickly
   (as seen above — iters 2/3 produce identical coverage to iter-1).
3. **petclinic's `@ModelAttribute`-resolved path-vars are unmodeled.** This
   session's #3 fix landed (`Parameter.annotationValues`, `DomainAnalyzer`
   extraction, `SampleInputGenerator.paramKey` — verified with unit tests
   for `@PathVariable("name") Custom owner`, `@PathVariable(value=…)`,
   `@PathVariable(name=…)`). But spring-petclinic 4.0 binds path vars at
   the controller-class level via `@ModelAttribute findOwner(@PathVariable
   Integer ownerId)` rather than on individual handler methods, so the
   methods themselves have empty parameter lists and `SampleInputGenerator`
   has nothing to key. The IterationRunner's defensive filter quarantines
   those paths cleanly. Cross-method `@ModelAttribute` resolution is its
   own analyzer feature, distinct from the `@PathVariable("x")` aliasing
   case that #3 closes.
4. **`-Pagent.enabled=true` build flag still required.** Default-build
   `ArchiveShutdownWriter` references the source-excluded
   `JdbcAgentBaggageBridge`. Out of scope for this session; documented as
   a stretch goal in the prior session's handoff §8.
5. **`jdbc-intercept-agent` repo is private.** Anyone reproducing this
   needs read access plus the Maven Local publish step in §Prereqs.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `Stage 1 produced zero endpoints — halting iteration` | `--sut-source` doesn't point at `src/main/java` | check the absolute path |
| `SUT process exited before becoming healthy` at Stage 3 | Postgres not up, or port 55432 in use | `docker compose -f samples/scout/petclinic/docker-compose.yml ps`; check for stale containers |
| Stage 4: `endpoint not found in archive` | All paths for this endpoint were strict-mode quarantined | inspect `iter-N/stage3-archive/quarantine/` |
| Stage 5: `Formatting violations` from spring-javaformat | shouldn't happen — wrapper sets `-Dspring-javaformat.skip=true` | verify the wrapper hasn't been edited |
| Stage 6: `could not parse jacoco.xml` | DOCTYPE not stripped (single-line XML edge case) | verify wrapper's `sed -E 's@<!DOCTYPE...@@g'` substitution path |
| `baseURI cannot be null` on every generated test | known limitation §3.1 — RestAssured isn't configured | document; not fixable without test-generator changes |

## Pipeline architecture (one-screen reference)

```
orchestrator CLI
   │
   ▼
IterationRunner.runOne(iter=N)
 ├─ Stage 1   AST → Domain → Branch ─► iter-N/stage1-discovery/{paths,endpoints}.json
 │            (defensive: drop paths whose URL placeholders are unbound)
 ├─ Stage 2   ScoutStepTranslator ─► iter-N/stage2-config.yml
 │            (post-edit: output.archive-dir ← iter-N/stage3-archive)
 │            (post-edit: resolve ${VAR} env-var placeholders)
 ├─ Stage 3   scout-launcher iter-N/stage2-config.yml
 │            └─► iter-N/stage3-archive/<path-id>/{captured_sql,paths,…}.json
 │            (+ quarantine/<path-id>/ for status-mismatch paths)
 ├─ Stage 3.5 aggregate per-path-id archive → flat root endpoints.json + paths.json
 ├─ Stage 4   test-generator (per non-quarantined endpoint)
 │            └─► iter-N/stage4-tests/<pkg>/*.java
 ├─ Stage 5   ./scripts/petclinic-stage5.sh iter-N/stage4-tests iter-N/stage5-jacoco.xml
 │            ├─ glob iter-*/stage4-tests/. → cp into petclinic src/test/java/
 │            ├─ ensure rest-assured dep in pom.xml (ephemeral)
 │            ├─ mvn -q -DskipITs -Dspring-javaformat.skip=true
 │            │      -Dmaven.test.failure.ignore=true test jacoco:report
 │            ├─ sed -E 's@<!DOCTYPE report ...@@g' on the JaCoCo XML
 │            └─ EXIT trap restores pom.xml and removes injected tests
 └─ Stage 6   JaCoCoXmlParser + CoverageDeltaCalculator + TerminationDecision
              └─► iter-N/stage6-feedback/{coverage-delta,termination-decision,next-iteration-hints}.json
```
