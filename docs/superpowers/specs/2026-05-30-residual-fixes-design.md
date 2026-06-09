# Residual Fixes — Design Spec

> Date: 2026-05-30
> Builds on PR #13 + the runbook-fixes follow-up commits (range `f2f3440..3e89af5`)
> New branch: `feat/petclinic-e2e-residuals` (off `feat/petclinic-e2e`)

## 0. Summary

Two surgical fixes that close the residuals documented in `docs/orchestrator-e2e-petclinic.md` §"Known limitations" after the runbook-fixes round:

- **#1** Bump RestAssured from 5.4.0 to 5.5.0 in the Stage 5 wrapper's pom inject. The 5.5 line uses Groovy 4.x with proper JDK 17 compatibility, eliminating the `Class.isAssignableFrom(null)` NPE inside `ClosureMetaClass.invokeOnDelegationObject` that today errors every generated test before its HTTP request fires.
- **#3** Add a URL-driven path-var auto-fill at the bottom of `SampleInputGenerator.generate(...)`. Any `{xxx}` placeholder in the endpoint URL that's not bound by an explicit `@PathVariable` parameter (petclinic's `@ModelAttribute findOwner(...)` controller pattern) gets a default value of `"1"`. Eleven endpoints currently quarantined at Stage 2 with messages like "missing [ownerId]" become bindable.

Together these should:
- Take the 4 currently-erroring generated tests from "Groovy NPE before request" to "actual HTTP call hits petclinic" (#1).
- Drop the Stage-2 quarantine count from 11 toward 0 for petclinic, unlocking ~10 additional endpoints to flow through Stages 3-5 (#3).
- Move branch coverage above the petclinic-own-tests floor of 0.561.

Out of scope: the boundary-value-only path explorer redesign (runbook §2), `-Pagent.enabled=true` cleanup (§4), `jdbc-intercept-agent` access (§5). `@ModelAttribute` modeling at the analyzer level is also explicitly deferred — we're applying a URL-driven workaround, not modeling Spring semantics.

## 1. Goals & non-goals

### 1.1 Goals

1. The Python heredoc in `scripts/petclinic-stage5.sh` injects `<version>5.5.0</version>` for `rest-assured`. Existing wrapper tests stay GREEN.
2. `SampleInputGenerator.generate(...)` post-processes every `NamedSampleInput` to ensure `pathParams` contains every `{name}` placeholder appearing in `endpoint.path()`, defaulting missing keys to `"1"`.
3. A new `SampleInputGeneratorTest` method exercises the parameter-less handler + URL-with-placeholder case (`initUpdateOwnerForm` shape) and asserts `pathParams` ends up `{"ownerId": "1"}`.
4. Live 5-iter re-run produces: (a) generated tests no longer NPE; (b) Stage-2 quarantine count drops; (c) the runbook's reference table reflects the new numbers.

### 1.2 Non-goals

- Modeling `@ModelAttribute findOwner(@PathVariable ...)` at the analyzer level. The URL-driven fill is a workaround that doesn't depend on parsing Spring's cross-method binding semantics.
- Picking type-aware default values (e.g., `"abc"` for string path-vars, UUID for `{uuid}`). Petclinic is integer-IDed throughout; `"1"` covers it.
- Re-architecting `TestSynthesizer` to use `@SpringBootTest` / `WebTestClient`. We're keeping RestAssured because the version bump is dramatically smaller scope.
- Removing the `--add-opens` argLine from the wrapper. It's harmless on 5.5.x and provides belt-and-suspenders in case 5.5.x's Groovy machinery still needs occasional reflection access.

## 2. Component changes

### 2.1 `scripts/petclinic-stage5.sh` — RestAssured version

Single-token change inside the existing Python heredoc:

```diff
 inject = '''    <dependency>
       <groupId>io.rest-assured</groupId>
       <artifactId>rest-assured</artifactId>
-      <version>5.4.0</version>
+      <version>5.5.0</version>
       <scope>test</scope>
     </dependency>
   </dependencies>'''
```

If 5.5.0's live run still NPEs in the Groovy code path, the implementer iterates to `5.5.1`, `5.5.2`, … `5.5.5` (in order, picking the latest known-good). The spec authorizes that iteration explicitly so it doesn't require a fresh approval cycle.

### 2.2 `graph-rag-builder/.../SampleInputGenerator.java` — URL placeholder fill

Add three private static elements and one call from `generate(...)`:

```java
private static final java.util.regex.Pattern URL_PLACEHOLDER =
        java.util.regex.Pattern.compile("\\{([^}]+)\\}");

private static Set<String> placeholdersOf(String urlTemplate) {
    Set<String> out = new java.util.LinkedHashSet<>();
    java.util.regex.Matcher m = URL_PLACEHOLDER.matcher(urlTemplate);
    while (m.find()) out.add(m.group(1));
    return out;
}

private static NamedSampleInput fillUnboundPlaceholders(
        NamedSampleInput named, Set<String> placeholders) {
    Map<String, String> existing = named.input().pathParams();
    if (existing.keySet().containsAll(placeholders)) return named;
    LinkedHashMap<String, String> filled = new LinkedHashMap<>(existing);
    for (String p : placeholders) filled.putIfAbsent(p, "1");
    return new NamedSampleInput(
            named.name(), named.expectedStatus(),
            new SampleInput(named.input().headers(), filled,
                            named.input().queryParams(), named.input().body()));
}
```

In `generate(...)`, replace the final `return List.copyOf(out);` with:

```java
Set<String> phs = placeholdersOf(endpoint.path());
if (phs.isEmpty()) return List.copyOf(out);
return out.stream().map(ni -> fillUnboundPlaceholders(ni, phs)).toList();
```

Imports added at top: `java.util.LinkedHashSet`, `java.util.regex.Pattern`, `java.util.regex.Matcher` (already present in some related files; add as needed).

### 2.3 New unit test

`SampleInputGeneratorTest.unboundUrlPlaceholdersAreDefaultedWhenMethodHasNoMatchingParameter`:

```java
@Test
void unboundUrlPlaceholdersAreDefaultedWhenMethodHasNoMatchingParameter() {
    MethodAnalysis ma = new MethodAnalysis(
            "demo.Ctrl", "edit",
            java.util.List.of(),
            java.util.List.of(), java.util.List.of(),
            io.graphrag.builder.staticanalysis.domain.ReturnType.of("void"));
    Endpoint ep = new Endpoint(
            "GET:/owners/{ownerId}/edit", io.graphrag.model.HttpMethod.GET,
            "/owners/{ownerId}/edit", "demo", "demo.Ctrl", "edit",
            false, java.util.List.of());

    java.util.List<NamedSampleInput> inputs = SampleInputGenerator.generate(
            ep, ma, BoundaryValueConfig.defaults(), item -> {});

    assertThat(inputs.get(0).input().pathParams())
            .as("URL placeholder {ownerId} should be filled with default \"1\" when handler has no @PathVariable")
            .containsExactly(java.util.Map.entry("ownerId", "1"));
}
```

### 2.4 No wrapper-test change

The pom-inject Python heredoc is executed at runtime against a fake petclinic clone in the existing wrapper tests, but the `if ! grep -q 'rest-assured' ...` guard short-circuits when rest-assured is already present. We could add a test that greps the wrapper-source for the version string but that's tautological. The live re-run is the real verification.

## 3. Acceptance criteria

- [ ] `:graph-rag-builder:test` GREEN — 175 (3 Neo4j-skipped) including the new placeholder-fill test.
- [ ] `:orchestrator:test` GREEN — unchanged from the prior round (9 tests).
- [ ] `./gradlew -Pagent.enabled=true check` GREEN.
- [ ] Live 5-iter re-run completes; `final-report.md` is emitted.
- [ ] Stage 1 quarantine count drops below 11 (ideally 0 or close to it, modulo edge cases like the `static_showResourcesVetList_happy` path that has a quote-wrapped placeholder string).
- [ ] Surefire reports `Errors: 0` (or substantially lower than 4) for the generated tests — the Groovy NPE is gone with RestAssured 5.5.x.
- [ ] Branch coverage on at least one iteration is strictly greater than 0.561 (the petclinic-own-tests floor).
- [ ] `docs/orchestrator-e2e-petclinic.md` updated with new reference numbers and revised limitations list.

## 4. Decision log

| # | Decision | Rationale | Alternatives rejected |
|---|---|---|---|
| D1 | Bump RestAssured to 5.5.0 (not 5.5.5 / not 5.5.x latest) | 5.5.0 is the first release in the Groovy-4 line; if it works, no need to chase newer patch versions. The spec authorizes iteration to newer 5.5.x if 5.5.0 still NPEs. | Switch to `@SpringBootTest`/`WebTestClient` (much bigger scope); stay on 5.4.0 (already known to NPE) |
| D2 | URL-driven default `"1"` for unbound placeholders | Smallest fix that unblocks petclinic. Works because petclinic seeds IDs 1-N. Doesn't claim to model `@ModelAttribute` — keeps that as a future analyzer feature. | Type-aware defaults (over-engineered); `@ModelAttribute` parsing (50-80 LOC vs ~15); orchestrator-side fill (wrong layer) |
| D3 | Fill applies to ALL `NamedSampleInput` variants, including boundary | Boundary inputs vary ONE param at a time, leaving the others at happy values. Without fill, boundary inputs for `id` on `/owners/{ownerId}/pets/{id}` would have `ownerId` unbound. Fill is no-op when key exists, so it doesn't disturb existing variants. | Fill only `happy` input (creates inconsistency between variants) |
| D4 | Keep `--add-opens` argLine from prior session | Belt-and-suspenders; harmless on 5.5.x; saves a rollback commit if 5.5.x ever needs occasional reflection access. | Drop it (small cleanup but no net benefit) |
| D5 | New PR branched off `feat/petclinic-e2e`, targets `main` | PR #13 isn't merged yet; this work stacks on top. Reviewer can compare new PR against PR #13's tip to see only the residual fixes. | Add commits to PR #13 (muddies that PR's already-large diff); branch off `main` (can't reuse the SUT-launch wrapper) |

## 5. References

- `docs/orchestrator-e2e-petclinic.md` — runbook with current limitations.
- `docs/superpowers/specs/2026-05-30-runbook-fixes-design.md` — prior session's spec (what this builds on).
- `graph-rag-builder/src/main/java/io/graphrag/builder/staticanalysis/branch/SampleInputGenerator.java` — current code.
- `scripts/petclinic-stage5.sh` — current wrapper (commit `a7e54ed` tip).
- RestAssured 5.5.0 release notes — switched to Groovy 4.0.x.
