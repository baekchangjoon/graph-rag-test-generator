# Real-Petclinic E2E — Design Spec

> Date: 2026-05-30
> Supersedes: `docs/superpowers/specs/2026-05-29-graph-rag-builder-t3-cli-switch-design.md` §6 (real-petclinic E2E sub-section)
> Inputs: `docs/handoff/2026-05-30-next-session.md`

## 0. Summary

Run the existing 6-stage orchestrator end-to-end against the real `spring-petclinic` SUT (Postgres + jdbc-intercept-agent + OTEL baggage + scout-launcher + test-generator + maven + JaCoCo), in a coverage-feedback loop of up to 5 iterations, and prove the work-order's "≥ 70 % branch coverage reachable" acceptance criterion. Deliver a reproducible runbook and the small set of wiring fixes that make the loop actually compose against a real SUT (which it could not, as merged on `main`).

## 1. Goals & non-goals

### 1.1 Goals

1. The orchestrator binary, built via `:orchestrator:installDist`, runs against real petclinic with one config file + one wrapper script, and writes `iter-N/{stage1..stage6}/...` on disk plus `final-report.md`.
2. Branch coverage is **cumulative** across iterations — measured against ALL prior + current iters' generated tests, not just the current iter's subset. Without this the loop cannot demonstrate monotone coverage growth, because Stage 6 prunes already-covered endpoints from later iters' inputs.
3. Two pre-existing wiring holes are fixed (see §2.2 and §2.3) — both are blocking; without them, scout-launcher writes archives to a fixed path that the orchestrator's iter-scoped reader cannot find, and the Stage-5 wrapper script has no way to know which iter-N to operate on.
4. The runbook (`docs/orchestrator-e2e-petclinic.md`) documents actual numbers from one live run — coverage per iter, manual-review queue contents, quarantined paths — so a fresh operator can reproduce.

### 1.2 Non-goals

- T3 path-explorer features deferred from the prior session (`@NotNull` null-variants, enum permutations, `@ExceptionHandler` exitStatus inference) — out of scope.
- Default-build fix removing the `-Pagent.enabled=true` requirement — stretch only, not blocking.
- `MethodAnalysis.outgoingCalls` population and `BranchKind.RETURN` emission — stretch only.
- Wiring the orchestrator into CI. The acceptance is a manual local run + committed runbook.

## 2. The work surface

### 2.1 New artifacts

- **`samples/scout/petclinic/template.yml`** — copy of `config.yml` minus the `scout.steps` block (the `ScoutStepTranslator` overwrites the entire `scout:` section per-iteration). Also flips `dependencies.docker-compose.teardown-on-exit: false` so Postgres + Redis + Kafka stay up across iterations (saves ~30 s × 5 ≈ 2.5 min); user tears down via `docker compose down` at the end.
- **`scripts/petclinic-stage5.sh`** — Bash wrapper, two positional args (`<tests-dir> <jacoco-out>`), drives maven + jacoco on petclinic with cumulative-coverage semantics. See §3.2 for the full contract.
- **`docs/orchestrator-e2e-petclinic.md`** — runbook, written AFTER a green live run with actual numbers.

### 2.2 Fix 1 — orchestrator rewrites `output.archive-dir` per iteration

`ExternalStageRunner.Shell.runScout(configYaml, archiveDir)` takes an `archiveDir` arg but ignores it: it just `spawn(scout-launcher configYaml)`. The launcher writes archives to wherever `output.archive-dir` in the YAML says. That value comes from `template.yml`, a fixed string, so every iteration's scout pass stomps the same `/tmp/...` dir, and `runTestGenerator(stage3Archive, ...)` then reads from `iter-N/stage3-archive` (different path) — empty.

**Fix**: in `IterationRunner.runOne`, after `ScoutStepTranslator.translate(...)` writes `stage2-config.yml`, the runner re-reads that YAML, sets `output.archive-dir = layout.stage3Archive().toString()`, and writes it back. Keeps `ScoutStepTranslator` focused on the `scout:` block (its existing concern). New unit test pins both branches: YAML with and without an existing `output.archive-dir`.

### 2.3 Fix 2 — `Shell.runTestsAndJacoco` passes args to the user command

`Shell.runTestsAndJacoco(generatedTestsDir, jacocoOut)` ignores both args and just `spawn(userTestCommand)`. The wrapper script therefore has no way to know which `iter-N/stage4-tests` to copy or where to write `jacoco.xml` — both are per-iteration.

**Fix**: append `generatedTestsDir` and `jacocoOut` as positional args to the spawned `userTestCommand`. So `userTestCommand = "./scripts/petclinic-stage5.sh"` becomes `./scripts/petclinic-stage5.sh /…/iter-1/stage4-tests /…/iter-1/stage5-jacoco.xml`. Explicit, easy to debug, no env-var indirection. New unit test uses a temp `echo-args.sh` to assert argv.

### 2.4 No new orchestrator surface

`OrchestratorConfig`, `Orchestrator` CLI flags, `IterationLayout`, and `ReportGenerator` are untouched. The Stage 6 feedback loop (`CoverageDelta`, `TerminationDecision`, `FocusHintGenerator`) is untouched.

## 3. Component contracts

### 3.1 `template.yml`

```
sut: …                                # identical to config.yml
dependencies:
  docker-compose:
    file: ./samples/scout/petclinic/docker-compose.yml
    wait-for-healthy: true
    teardown-on-exit: false           # CHANGED from config.yml
    health-timeout-seconds: 120
output:
  archive-dir: /tmp/graph-rag-scout/petclinic-archive    # placeholder — orchestrator overwrites per iter
  clear-before-run: true
  preserve-files: [paths.json, endpoints.json]
  strict-mode: true
  project: petclinic
# NO scout: section — translator injects it
```

### 3.2 `scripts/petclinic-stage5.sh`

**Contract**: positional args `<tests-dir> <jacoco-out>`. Reads env `PETCLINIC_DIR` (default `${HOME}/github_spring-petclinic/spring-petclinic`), `TEST_PACKAGE` (default `com.example.petclinic.tests`).

**Behavior**:

1. `set -euo pipefail`.
2. Validate args, validate `PETCLINIC_DIR` exists and contains `pom.xml`.
3. Compute `OUT_DIR = $(realpath "$(dirname "$1")/..")` — the orchestrator's outDir.
4. `TEST_SRC = $PETCLINIC_DIR/src/test/java/$(echo $TEST_PACKAGE | tr . /)`. Cleanup trap: `rm -rf "$TEST_SRC"` on EXIT.
5. `mkdir -p "$TEST_SRC"`. For each `iter-*/stage4-tests/` directory under `OUT_DIR` (sorted lexicographically), `cp -r ${dir}/. "$TEST_SRC"/`. Last-write-wins on file collisions.
6. `cd "$PETCLINIC_DIR" && mvn -q -DskipITs test jacoco:report`. If mvn fails non-zero, the script propagates (trap still cleans).
7. `cp "$PETCLINIC_DIR/target/site/jacoco/jacoco.xml" "$2"`. Error if missing.
8. EXIT trap fires, restoring petclinic clone.

### 3.3 `IterationRunner` archive-dir patch

After the existing `ScoutStepTranslator.translate(...)` call:

```java
// Stage 2 step (b): repoint scout-launcher's archive output at this iter's slot.
ObjectMapper yaml = new ObjectMapper(new YAMLFactory()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
ObjectNode root = (ObjectNode) yaml.readTree(layout.stage2Config().toFile());
root.with("output").put("archive-dir", layout.stage3Archive().toString());
Files.write(layout.stage2Config(),
        yaml.writerWithDefaultPrettyPrinter().writeValueAsString(root).getBytes());
```

Unit test: hand-crafted template with and without `output:` node, assert post-edit value equals iter-N's `stage3-archive` path.

### 3.4 `Shell.runTestsAndJacoco` arg passthrough

```java
@Override
public void runTestsAndJacoco(Path generatedTestsDir, Path jacocoOut)
        throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>(userTestCommand);
    cmd.add(generatedTestsDir.toString());
    cmd.add(jacocoOut.toString());
    spawn(cmd);
    if (!Files.exists(jacocoOut)) {
        throw new IOException("expected JaCoCo XML at " + jacocoOut
                + " but the user test command did not produce it");
    }
}
```

Unit test: write a temp shell script that writes its argv to a sentinel file + touches the requested `jacocoOut`; assert sentinel records both paths.

## 4. Acceptance criteria

- [ ] `samples/scout/petclinic/template.yml` committed; diff vs `config.yml` is exactly the `scout.steps` removal + the `teardown-on-exit: false` flip.
- [ ] `scripts/petclinic-stage5.sh` committed; executable bit set; passes its unit test against fixture `iter-*/stage4-tests` directories with a stub `mvn` on `PATH`.
- [ ] `IterationRunner` archive-dir patch + unit test.
- [ ] `Shell.runTestsAndJacoco` arg-passthrough patch + unit test.
- [ ] `:graph-rag-builder:test` and `:orchestrator:test` stay GREEN.
- [ ] One full 5-iter local run completes without crash and emits `final-report.md` with non-empty coverage numbers. Reaching 0.70 branch coverage preferable but NOT blocking (the work-order's wording says "within 70 % branch coverage on petclinic" — interpreted as upper-bound expectation, not lower-bound floor).
- [ ] `docs/orchestrator-e2e-petclinic.md` written from the actual run output, committed.

## 5. Out of scope / explicit non-goals (re-stated for clarity)

- Refactoring `ArchiveShutdownWriter` to make default-build green without `-Pagent.enabled=true`.
- Enriching `MethodAnalysis.outgoingCalls`, emitting `BranchKind.RETURN`.
- Adding `@NotNull`/enum/`@ExceptionHandler` heuristics to the path explorer.
- Wiring the live E2E into CI.

## 6. Decision log

| # | Decision | Rationale | Alternatives rejected |
|---|---|---|---|
| D1 | Test injection = copy into petclinic `src/test/java`, clean on exit | Simplest path; pollution is bounded by EXIT trap | Separate Maven module (more build wiring), `<testSourceDirectory>` override (needs an extra compile step) |
| D2 | Coverage target = 0.70 | Work-order acceptance number | 0.50 too easy; 0.85 unreachable with boundary-value-only explorer |
| D3 | Max iterations = 5 | Slack beyond the 3-iter target so the no-progress heuristic can demonstrate | 3 too tight; 10 too slow (Docker + mvn × N) |
| D4 | Archive-dir rewrite lives in `IterationRunner`, not `ScoutStepTranslator` | Keeps translator focused on `scout:` block; isolates the per-iter concern in the runner | Extending translator (broader API surface); env-var override in launcher (touches three modules) |
| D5 | Stage-5 args = positional | Most explicit; trivially debuggable | Env vars (less explicit); `current-iter` symlink (race-prone) |
| D6 | Cumulative coverage = wrapper globs `iter-*/stage4-tests` | No orchestrator contract change; matches spec intent (coverage grows monotonically) | Per-iter snapshot (oscillates, breaks the loop's value prop); orchestrator-maintained cumulative dir (spreads change across more files) |

## 7. References

- `docs/handoff/2026-05-30-next-session.md` — operating prereqs, prior-session state, work order.
- `samples/scout/petclinic/{config.yml,docker-compose.yml,README.md}` — the inputs we copy/transform from.
- `orchestrator/src/main/java/io/graphrag/orchestrator/{Orchestrator,IterationRunner,ExternalStageRunner,IterationLayout}.java` — surface we touch.
- `scout-step-translator/src/main/java/io/graphrag/translator/ScoutStepTranslator.java` — left untouched; understood for the archive-dir patch.
