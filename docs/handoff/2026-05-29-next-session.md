# Next Session Handoff — graph-rag-builder T3 + CLI + Orchestrator Switch

> **For the LLM agent picking this up in a fresh session.**
>
> Date authored: 2026-05-29
> Branch: `feat/t6-orchestrator` (head: `ae021b6`)
> Working directory: `/Users/changjoonbaek/graph-rag/graph-rag`
> Build: `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./gradlew -Pagent.enabled=true ...`

---

## 0. Quick-start prompt for the fresh session

Paste this verbatim to start the next session:

> 다음 작업지시서대로 진행해줘.
> @docs/handoff/2026-05-29-next-session.md
> @docs/superpowers/specs/2026-05-28-graph-rag-builder-static-analysis-design.md
> @/Users/changjoonbaek/.claude/uploads/809eed39-b411-46a7-a760-572833317fe0/080169fc-graphragbuilderstaticanalysisworkorder.md

The agent should run `superpowers:brainstorming` first (HARD-GATE) to scope this session, then `superpowers:writing-plans`, then `superpowers:subagent-driven-development` for execution.

---

## 1. Where we are (verified)

### 1.1 Committed work on this branch (since `42fcfc8`)

```
ae021b6 chore(staticanalysis): final-review nits — docs + CallGraph integration assertion
5408d52 test(staticanalysis): petclinic fixture + DomainAnalyzer integration test
4a96c92 docs(staticanalysis): correct DomainAnalysisResult sort-order javadoc
5e49cb0 feat(staticanalysis): DomainAnalyzer — classify → endpoints → methods → call graph
97023e2 feat(staticanalysis): EndpointExtractor auth — @PreAuthorize/@Secured/@RolesAllowed
39a4839 feat(staticanalysis): EndpointExtractor — mapping detection + path join
c1cf0f2 feat(staticanalysis): CallGraphBuilder — in-project edges, unresolved-call tolerance
f34e6d6 feat(staticanalysis): BranchExtractor — IF/SWITCH/TERNARY/THROW extraction with refs
840e64f feat(staticanalysis): ClassRoleClassifier — controller/service/repository/domain
d8a6d79 feat(staticanalysis): domain records (roles, branches, calls, analyses)
40de167 feat(staticanalysis): AstParser — deterministic file walk + per-file failure isolation
1e53d87 feat(staticanalysis): SymbolResolverFactory with reflection+source+jar solvers
0d1b601 feat(staticanalysis): ast records (ParsedFile, ParseFailure, AstParseResult)
08a6138 chore(staticanalysis): scaffold ast/ + domain/ packages with JavaParser dep
42fcfc8 docs: design spec for graph-rag-builder static analysis T1+T2
c5b2bd8 feat(t6): orchestrator — 6-stage pipeline + coverage-feedback loop
fd546fe fix(t5): TerminationDecision exempts iter 1 from no-progress rule
```

### 1.2 What's working

- `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/ast/` — `AstParser`, `SymbolResolverFactory`, `ParsedFile`, `ParseFailure`, `AstParseResult`. 6 unit tests + 3 unit tests GREEN.
- `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/domain/` — `ClassRoleClassifier`, `BranchExtractor`, `CallGraphBuilder`, `EndpointExtractor`, `DomainAnalyzer`, `DomainAnalysisResult`, and 9 supporting records/enums. Per-component unit tests (11 + 7 + 4 + 13 + 4) all GREEN. Petclinic integration test pinning every T1+T2 acceptance criterion GREEN.
- `orchestrator/` module — calls `io.graphrag.discovery.PathDiscoveryStatic.discover(...)` from `path-discovery-static/`. E2E test with `FakeExternal` GREEN. **Must continue working after the switch.**
- `path-discovery-static/` — still the live Stage 1 implementation that the orchestrator depends on.

### 1.3 What this session needs to do

From the prior spec §11 ("Next session checklist"):

1. Add `staticanalysis/branch/` package — branch analysis + SampleInput generation.
2. Add `staticanalysis/cli/` package — CLI entry point + JSON file output.
3. Switch `orchestrator/IterationRunner` from `PathDiscoveryStatic.discover` to the new graph-rag-builder API (in-process or subprocess).
4. Delete the `path-discovery-static` module.
5. Verify `orchestrator/OrchestratorE2eTest` (FakeExternal) still passes.

Out of scope (subsequent session): **real-petclinic E2E** — requires `mvn -DskipTests package` on spring-petclinic, a Postgres docker-compose, a Stage-5 wrapper that copies generated tests into petclinic's test source tree and runs JaCoCo, etc. Significant integration work that should be its own focused session.

---

## 2. Reference documents to read first

In priority order:

1. **`docs/handoff/2026-05-29-next-session.md`** (this file) — context + scope.
2. **`docs/superpowers/specs/2026-05-28-graph-rag-builder-static-analysis-design.md`** — the T1+T2 spec; §11 lists this session's checklist verbatim.
3. **`docs/superpowers/plans/2026-05-28-graph-rag-builder-static-analysis.md`** — T1+T2 implementation plan; useful as a template for the new plan.
4. **The uploaded work order** (the user attaches this in their first message): `graph-rag-builder-static-analysis-work-order.md` — contains T3 detail in §8 and CLI detail in §9.
5. **`docs/superpowers/specs/2026-05-28-graph-rag-builder-static-analysis-design.md` §11** — "Next session checklist."

---

## 3. Required decisions to make in this session (open)

Ask the user explicitly via `AskUserQuestion` rather than guessing:

### 3.1 Orchestrator integration mode

Two options:

- **In-process call** — `IterationRunner` calls `DomainAnalyzer.analyze(...)` directly + a new `branch/` package's `ExploredPathBuilder.build(...)` for `ExploredPath[]`. Faster, no subprocess overhead. Adds an `:orchestrator` → `:graph-rag-builder` Gradle dependency. **Recommended** for symmetry with current `:path-discovery-static` usage.
- **Subprocess CLI** — `IterationRunner` shells out to a `staticanalysis-cli` install-dist binary. Decouples build but adds process overhead and requires `installDist` in orchestrator's setup. The work order §9 actually wants this CLI to exist regardless (for standalone usage), so this option does NOT free us from building the CLI.

A reasonable resolution: **build both** — keep the in-process API for the orchestrator and ship the CLI for standalone usage. They share the same `DomainAnalyzer` + `branch/` code; the CLI is a thin wrapper.

### 3.2 `PathExplorerKind` enum value for static-AST

Currently the enum has `MANUAL/JDART/FUZZER/EVOSUITE`. The work order §11 recommended reusing `MANUAL` to avoid `shared-model` change risk.

`path-discovery-static/.../ExploredPathBuilder` currently uses `PathExplorerKind.MANUAL`. **Keep that decision.** When the new `branch/ExploredPathBuilder` is written, also set `discoveredBy = PathExplorerKind.MANUAL`.

### 3.3 T3 scope — how deep

The work order §8 lists ~9 components. To stay on budget:

- **Must have** — happy path + at least 1 boundary-value variant per primitive/string param. Deterministic enough to be idempotent across iterations.
- **Nice to have** — full boundary set (0, -1, MAX, MIN, null, "", long string, special chars), `@NotNull` → null variant, enum permutations, `@ExceptionHandler` → exitStatus inference.
- **Skip** — `PathConstraintBuilder` (the work order itself marks this 선택 구현). Constraint extraction is symbolic-execution territory.

Recommend: ship the **must have**, document the **nice to have** as TODO in the new spec, skip the rest.

### 3.4 path-discovery-static deletion timing

After the orchestrator switch is in place and `OrchestratorE2eTest` GREEN, delete the module in a separate commit (clean diff). Don't try to delete it before the switch.

---

## 4. Workflow the next session should follow

Per the `superpowers` skill chain:

1. **`superpowers:brainstorming`** (HARD-GATE) — scope this session, agree on approach, write a new spec under `docs/superpowers/specs/2026-05-29-graph-rag-builder-t3-cli-switch-design.md`.
2. **`superpowers:writing-plans`** — write a 15-20 task TDD plan under `docs/superpowers/plans/2026-05-29-graph-rag-builder-t3-cli-switch.md`. Follow the same template as the T1+T2 plan: bite-sized tasks, exact code, TDD failing-test-first.
3. **`superpowers:subagent-driven-development`** — dispatch tasks one at a time. Two-stage review (spec then quality) after each.

### Subagent dispatch lessons learned (from the T1+T2 session)

- **Use `haiku` for**: records, enums, mechanical scaffolding, simple classifiers, doc tweaks. The plan-provided code is essentially copy-paste.
- **Use `sonnet` for**: AST traversal (BranchExtractor, branch analyzers), symbol-solver integration, orchestrators (DomainAnalyzer), CLI argument parsing. Anywhere there's actual logic.
- **NEVER tell reviewer subagents to use `git checkout`** — it detaches HEAD and we lose commits. The previous session had to recover with `git branch -f`. Always instruct: "Use only `git diff <base>..<head>`, `git show`, `Read`."
- **Build flag is mandatory**: every gradle command must include `-Pagent.enabled=true`. The default build is broken for an unrelated reason (`ArchiveShutdownWriter` references `JdbcAgentBaggageBridge` which is source-excluded when the flag is off). Fixing that is a separate concern.
- **`Map.copyOf()` discards insertion order**. If a record needs `LinkedHashMap` ordering downstream, the compact constructor must do `Collections.unmodifiableMap(new LinkedHashMap<>(input))` not `Map.copyOf(input)`. We hit this in `DomainAnalysisResult` and fixed it mid-session.
- **Cumulative test count check** at the very end: full `:graph-rag-builder:test` must be GREEN, no regressions in `JdbcAgentBaggageBridgeTest`, `MyBatisDynamicSqlInterceptorTest`, etc.

### Estimated task count

15-20 tasks across:

- T3 (`branch/` package): ~8-10 tasks (BoundaryValueConfig record, BoundaryValueGenerator + tests, DeterministicBranchDetector + tests, ConditionParser + tests, SampleInputGenerator + tests, ExploredPathBuilder + tests, StaticAnalysisPathExplorer SPI + tests, BranchAnalyzer orchestrator + tests).
- CLI (`cli/` package): ~3 tasks (StaticAnalysisOptions + parser tests, StaticAnalysisCli main + JSON file output, end-to-end CLI test that runs the binary on the petclinic fixture).
- Orchestrator switch: ~2 tasks (replace `PathDiscoveryStatic.discover` call in `IterationRunner`; ensure `OrchestratorE2eTest` continues to pass; possibly add `:orchestrator` → `:graph-rag-builder` dependency).
- path-discovery-static deletion: 1 task (git rm + remove `:path-discovery-static` from `settings.gradle.kts` + ensure full repo build GREEN).
- Verification: 1 task (full test suite run, regression check).

---

## 5. Acceptance criteria for this session

- [ ] `staticanalysis/branch/` package builds + tests GREEN.
- [ ] `staticanalysis/cli/` package builds + `StaticAnalysisCliTest` invokes the CLI on the petclinic fixture and asserts the 3 output JSON files exist and parse correctly.
- [ ] Each endpoint in `paths.json` has ≥1 `ExploredPath` (happy path).
- [ ] `:graph-rag-builder:test` GREEN with `-Pagent.enabled=true`, no regressions.
- [ ] `IterationRunner` uses `DomainAnalyzer` (or the new CLI) instead of `PathDiscoveryStatic.discover`. `OrchestratorE2eTest` GREEN.
- [ ] `path-discovery-static/` directory removed; `settings.gradle.kts` no longer includes it; full repo build GREEN.
- [ ] No real petclinic mvn build attempted (deferred).

---

## 6. Real-petclinic E2E — separate future session

What's needed when we get to that:

- `mvn -DskipTests package` on `~/github_spring-petclinic/spring-petclinic` (produces the SUT jar).
- `samples/scout/petclinic/docker-compose.yml` brings up Postgres (already in repo, verified working).
- A **Stage 5 wrapper script** (e.g., `scripts/petclinic-stage5.sh`) that:
  - Copies generated tests from `iter-N/stage4-tests/` into the petclinic project's `src/test/java/`.
  - Runs `mvn test jacoco:report` on petclinic.
  - Copies `target/site/jacoco/jacoco.xml` to `iter-N/stage5-jacoco.xml` (the orchestrator's expected path).
- A `samples/scout/petclinic/template.yml` — like `config.yml` but with `scout.steps` removed so the translator can fill it in.
- `:orchestrator:installDist` binary.
- Acceptance: 3 iterations within 70% branch coverage on petclinic.

That session should produce a runbook (`docs/orchestrator-e2e-petclinic.md`) once the wrapper works.

---

## 7. Known limitations carried forward

- `-Pagent.enabled=true` build flag required. Default build has a pre-existing `ArchiveShutdownWriter` compile error that is out of scope.
- `BranchKind.RETURN` enum value exists but is not currently emitted. T3's branch analyzer can decide whether to start emitting it or document that RETURN sub-branches are handled implicitly via IF/SWITCH parent extraction.
- `MethodAnalysis.outgoingCalls` is currently `List.of()`. T3's branch analyzer may need to populate it; the wiring is already there.
- The mini petclinic fixture covers 5 endpoints; the spec §4.8 "≥10" criterion targets a real spring-petclinic source tree and was deferred.
- `PathExplorerKind.STATIC_AST` was considered but rejected — reuse `MANUAL` (no `shared-model` change).
