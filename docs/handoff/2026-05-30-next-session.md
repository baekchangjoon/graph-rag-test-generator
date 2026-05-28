# Next Session Handoff — Real-Petclinic E2E

> **For the LLM agent picking this up in a fresh session.**
>
> Date authored: 2026-05-30
> Prior session: PR #12 — `feat: 6-stage orchestrator + AST static-analysis pipeline (T1-T6 + T3 CLI switch)` merged into `main`
> Working directory: `/Users/changjoonbaek/graph-rag/graph-rag`
> Build: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew -Pagent.enabled=true ...`

---

## 0. Quick-start prompt for the fresh session

Paste this verbatim:

> 다음 작업지시서대로 진행해줘.
> @docs/handoff/2026-05-30-next-session.md

The agent should run `superpowers:brainstorming` first (HARD-GATE) to confirm scope, then `superpowers:writing-plans`, then `superpowers:subagent-driven-development` for execution. Same workflow as the last two sessions.

---

## 1. Where we are (verified at start of this session)

PR #12 (commits `f4e0966..ac61290`, 39 total) landed:
- 6-stage orchestrator loop (T1–T6) with coverage-feedback re-iteration
- `graph-rag-builder/staticanalysis/` package — `ast/` + `domain/` + `branch/` + `cli/` (T3 of work order + standalone CLI)
- Orchestrator `IterationRunner` driven by the new in-process `AstParser → DomainAnalyzer → BranchAnalyzer` pipeline
- Legacy `:path-discovery-static` module deleted

State on `main` post-merge:
- `:graph-rag-builder:test` 171 GREEN (3 Neo4j-required tests skipped intentionally)
- `:orchestrator:test` GREEN — `OrchestratorE2eTest` with **fake** external stages (Stages 3/4/5 simulated via `FakeExternal`)
- `gw check` GREEN across all 12 modules
- **Never validated end-to-end with a real Spring SUT.** That's this session.

---

## 2. This session's goal

Run the full 6-stage orchestrator loop against the real `spring-petclinic` SUT with Postgres in docker, JaCoCo coverage actually measured against the SUT's production code, and prove that 3 iterations can reach the spec's ≥70% branch coverage target.

Output: working runbook at `docs/orchestrator-e2e-petclinic.md` plus one or more commits adding the Stage-5 wrapper script + petclinic template config.

---

## 3. Inventory — what exists, what's missing

### 3.1 Already in the repo (verified)

- `samples/scout/petclinic/config.yml` — full scout-launcher config with hardcoded `scout.steps` for 3 endpoints (`list-owners`, `get-owner-1`, `list-vets`).
- `samples/scout/petclinic/docker-compose.yml` — Postgres on port 55432.
- `samples/scout/petclinic/README.md` — manual-run prereqs and runbook for scout-launcher only.
- `samples/scout/petclinic/manual-archive-seed/` — manual archive PoC from T1.
- `scout-step-translator` module — translates `paths.json` + `endpoints.json` into the scout.steps array given a template config. Currently used by `IterationRunner` Stage 2.
- `orchestrator/ExternalStageRunner.Shell` — production impl that shells out to scout-launcher and test-generator install-dist binaries and an arbitrary `userTestCommand` for Stage 5.

### 3.2 Missing — has to be built this session

1. **`samples/scout/petclinic/template.yml`** — copy of `config.yml` with the `scout.steps` block removed. `ScoutStepTranslator` will fill it in per iteration from `paths.json`.
2. **`scripts/petclinic-stage5.sh`** — the Stage-5 wrapper script that:
   - Copies generated tests from `iter-N/stage4-tests/` into `~/github_spring-petclinic/spring-petclinic/src/test/java/<package>/`.
   - Runs `mvn test jacoco:report` on petclinic.
   - Copies `target/site/jacoco/jacoco.xml` to a path the orchestrator's `runTestsAndJacoco(generatedTestsDir, jacocoOut)` expects.
   - Cleans up the copied tests at the end (or on next iteration's start) so iterations don't accumulate stale test files.
3. **Orchestrator main / CLI** — confirm `:orchestrator:installDist` works and the binary takes the same config flags as `OrchestratorConfig`. May need a thin `OrchestratorCli` if one doesn't exist yet.
4. **Cloned `spring-petclinic`** — needs to exist at `~/github_spring-petclinic/spring-petclinic` (per the existing config). If not, clone it: `git clone https://github.com/spring-projects/spring-petclinic.git ~/github_spring-petclinic/spring-petclinic`.
5. **`docs/orchestrator-e2e-petclinic.md`** — the runbook documenting how to run the full E2E.

### 3.3 Prereqs the user must verify (one-time setup)

```bash
# 1) Spring-petclinic source
test -d ~/github_spring-petclinic/spring-petclinic \
  || git clone https://github.com/spring-projects/spring-petclinic.git ~/github_spring-petclinic/spring-petclinic

# 2) Build the petclinic SUT jar
cd ~/github_spring-petclinic/spring-petclinic && mvn -DskipTests package

# 3) Publish bridges to Maven Local (required for scout-launcher)
cd ~/github_jdbc-intercept-agent/jdbc-intercept-agent && ./gradlew publishToMavenLocal
cd ~/graph-rag/graph-rag && JAVA_HOME=... ./gradlew -Pagent.enabled=true \
    :shared-model:publishToMavenLocal \
    :graph-rag-builder:publishToMavenLocal

# 4) Postgres up
cd ~/graph-rag/graph-rag && docker compose -f samples/scout/petclinic/docker-compose.yml up -d
```

If `~/github_jdbc-intercept-agent` doesn't exist locally, the user has to clone that private repo from `baekchangjoon/jdbc-intercept-agent` first.

---

## 4. Required decisions to make at session start

Ask the user explicitly via `AskUserQuestion` rather than guessing:

### 4.1 Test-injection strategy for Stage 5

Two options for getting the synthesized RestAssured tests into petclinic's classpath:

- **Copy into petclinic source tree** — `scripts/petclinic-stage5.sh` copies `iter-N/stage4-tests/**` into `~/github_spring-petclinic/spring-petclinic/src/test/java/<package>/`. Cleanup needed between iterations. Pollutes the user's clone but is the simplest path. **Recommended.**
- **Build a separate test jar** — copy tests into a synthetic Maven module, declare petclinic as a dep, run that module's `mvn test`. Cleaner but requires more build wiring.

### 4.2 JaCoCo configuration on petclinic

The stock `pom.xml` of spring-petclinic does include `jacoco-maven-plugin`. Verify with `grep jacoco ~/github_spring-petclinic/spring-petclinic/pom.xml`. If absent (depends on the version of petclinic), the wrapper script must inject the plugin or use a Maven profile override. Likely fine on `main`, but verify before assuming.

### 4.3 Coverage target for the acceptance test

Work order says "3 iterations within 70% branch coverage on petclinic." `OrchestratorConfig.coverageTarget` is currently set to `0.85` in `OrchestratorE2eTest` (with the fake JaCoCo). For the real run, set it to `0.70` per the work-order acceptance criterion.

### 4.4 Iteration cap

Recommend `maxIterations = 5` — gives slack beyond the 3-iteration acceptance target so the loop can demonstrate the no-progress termination heuristic.

---

## 5. Workflow the next session should follow

Per the `superpowers` skill chain:

1. **`superpowers:brainstorming`** (HARD-GATE) — scope the session, write a spec at `docs/superpowers/specs/2026-05-30-real-petclinic-e2e-design.md`.
2. **`superpowers:writing-plans`** — 8-12 task TDD plan at `docs/superpowers/plans/2026-05-30-real-petclinic-e2e.md`. Heavier than usual on shell-script + Docker integration; lighter on Java code.
3. **`superpowers:subagent-driven-development`** — dispatch tasks one at a time, two-stage review.

### Subagent dispatch lessons carried forward (from PR #12 session)

- Use `haiku` for: shell-script scaffolding, config-file authoring, README drafts.
- Use `sonnet` for: anything involving the orchestrator's process management, environment-variable wiring, error-path verification, or interpreting CI output.
- **Never tell reviewer subagents to use `git checkout`** — instruct `git diff <base>..<head>`, `git show`, `Read` only.
- **Build flag**: every gradle command needs `-Pagent.enabled=true` (the pre-existing `ArchiveShutdownWriter` compile error in default-build is still open — see §7).
- **Don't bundle build-config tweaks with feature commits** — the prior session had to add Spring BOM + JUnit pin in `orchestrator/build.gradle.kts` to make the `:graph-rag-builder` dep resolve. Keep that kind of mechanical fix in a separate, well-explained commit.

### Estimated task count

8-12 tasks across:

- Template config (1 task): `samples/scout/petclinic/template.yml`.
- Stage-5 wrapper script (2-3 tasks): script skeleton, test-copy logic, mvn invocation, jacoco.xml relocation, cleanup. Each step verifiable on a hand-crafted iter-N directory before tying into the orchestrator.
- Orchestrator CLI (1-2 tasks): verify `:orchestrator:installDist` exists / works; if not, add it. Confirm the install-dist binary parses an `OrchestratorConfig` from CLI flags.
- Postgres-up integration (1 task): pre-flight check in the runbook / wrapper that Postgres is alive before launching the loop.
- Smoke test (1 task): run 1 iteration end-to-end manually, confirm it terminates and writes `out/iter-1/{stage1..stage6}/`.
- Acceptance run (1 task): run 5 iterations with `coverageTarget=0.70`, confirm the loop reaches the target or stops via no-progress.
- Runbook (1 task): write `docs/orchestrator-e2e-petclinic.md` only after the live run is GREEN.
- Cleanup + commit hygiene (1 task): make sure scripts/, docs/, and samples/ are organized cleanly.

---

## 6. Acceptance criteria for this session

- [ ] `samples/scout/petclinic/template.yml` exists and matches `config.yml` minus `scout.steps`.
- [ ] `scripts/petclinic-stage5.sh` runs cleanly against a single iter-N directory drop and produces `jacoco.xml` at the orchestrator's expected path.
- [ ] `:orchestrator:installDist` produces a working binary; running it on the petclinic fixtures with `coverageTarget=0.70` and `maxIterations=5` completes without crashing.
- [ ] At least one iteration synthesizes ≥1 RestAssured test that actually compiles inside petclinic's source tree and runs (pass or fail; passing is preferable but not strictly required for this session — what matters is that the pipeline runs end-to-end).
- [ ] JaCoCo XML at `iter-N/stage5-jacoco.xml` parses through `JaCoCoXmlParser` and feeds the coverage-feedback loop.
- [ ] Final iteration outcome is documented in the runbook with actual numbers (which endpoints covered, which missed, what the manual-review queue contained).
- [ ] `docs/orchestrator-e2e-petclinic.md` published and committed.
- [ ] No regressions: `gw :graph-rag-builder:test :orchestrator:test` still GREEN.

---

## 7. Known limitations / risks carried forward

- **`-Pagent.enabled=true` build flag still required**: the default-build `ArchiveShutdownWriter` compile error in `graph-rag-builder/capture/` references `JdbcAgentBaggageBridge` which is source-excluded when the flag is off. Pre-existing, out of scope for this session — but if you have spare bandwidth at the end, this is a nice cleanup target (see §8).
- **Petclinic is an actively-maintained upstream**: the JPA entities, controller paths, and Spring versions may shift. Pin to a known-good commit if reproducibility matters: `git -C ~/github_spring-petclinic/spring-petclinic checkout <sha>`.
- **`jdbc-intercept-agent` is private**: anyone reproducing this needs the cloned repo + Maven Local publish. Document this prereq clearly in the runbook.
- **scout-launcher's strict-mode quarantine**: if Stage 1 over-predicts (e.g. all `id=-1` paths predict 404 but petclinic returns 200), scout-launcher quarantines the step. This is expected — the runbook should explain how to read the quarantine output.
- **Coverage feedback may oscillate**: with only the boundary-value heuristic in T3, the explorer doesn't actually adapt to coverage hints — it produces the same paths every iteration. Stage 6's `excludePaths` filter should still let coverage grow as new endpoints get covered by the synthesized tests. If iteration 2 produces identical paths.json to iteration 1, that's expected for now.
- **`StaticAnalysisOptions.maxPathsPerEndpoint >= 1` invariant**: the orchestrator hardcodes 10, but if you wire the CLI path, make sure your config doesn't pass zero (will fail validation, intentionally).

---

## 8. Stretch goals (only if §6 is GREEN with time to spare)

In priority order:

1. **Default-build fix**: refactor `ArchiveShutdownWriter` so it compiles without the agent dep (e.g., add a no-op fallback when `JdbcAgentBaggageBridge` is source-excluded). Removes the `-Pagent.enabled=true` requirement.
2. **Nice-to-have T3 features** (deferred from PR #12 session, see spec §1.2):
   - `@NotNull` → null-variant input (predicts 400)
   - Enum permutations (each enum value gets one variant)
   - `@ExceptionHandler` → exitStatus inference (currently always 200/201/400/404)
3. **`MethodAnalysis.outgoingCalls` population**: currently `List.of()`. The `CallGraphBuilder` already builds the graph; just need to wire the per-method calls into the `MethodAnalysis` record. Useful for follow-up symbolic-execution work.
4. **`BranchKind.RETURN` emission**: enum value exists but no branch analyzer emits it. Either start emitting it or remove the enum value to keep the surface honest.

These are all secondary to getting the E2E green. Don't start them until the runbook is committed.

---

## 9. Reference documents

In priority order:

1. **`docs/handoff/2026-05-30-next-session.md`** (this file).
2. **`docs/superpowers/specs/2026-05-29-graph-rag-builder-t3-cli-switch-design.md`** §1.2 (out-of-scope items deferred from the previous session) and §6 (real-petclinic E2E requirements).
3. **`docs/handoff/2026-05-29-next-session.md`** §6 (older real-petclinic spec — superseded by this file but useful for cross-reference).
4. **`samples/scout/petclinic/{config.yml,README.md}`** — existing petclinic infrastructure.
5. **`orchestrator/src/main/java/io/graphrag/orchestrator/{Orchestrator,IterationRunner,ExternalStageRunner,OrchestratorConfig}.java`** — orchestrator main surface.
6. **Work order**: `/Users/changjoonbaek/.claude/uploads/.../graphragbuilderstaticanalysisworkorder.md` — the original spec, mostly satisfied; the remaining "PathConstraintBuilder" (선택 구현 / optional) and the 4-week LOC estimate are aspirational and not session-scoped.
