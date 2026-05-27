# path-discovery-static

Stage 1 of the 6-stage pipeline. Walks a Spring source tree (`.java` files only,
no JARs / classpath / running app) and emits shared-model
`endpoints.json` + `paths.json` that drop straight into the
[archive layout](../docs/20-scout-launcher.md#5-archive-layout-produced) every
later stage consumes.

## CLI

```bash
./gradlew :path-discovery-static:installDist

./path-discovery-static/build/install/path-discovery-static/bin/path-discovery-static \
  --sut-source ~/github_spring-petclinic/spring-petclinic/src/main/java \
  --project    petclinic \
  --out        /tmp/petclinic-stage1
```

Optional:

| flag | use |
|---|---|
| `--code-version <sha>` | stamped into every `ExploredPath.code_version`. Default `static-1`. |
| `--exclude-paths id1,id2` | skip these endpoint ids. T5 coverage-feedback supplies this on iteration N+1 to avoid re-emitting paths the previous iteration already covered (R6 idempotency). |

## What it discovers

| Annotation | Recognized? |
|---|---|
| `@RestController` / `@Controller` | yes — controller class detection |
| `@RequestMapping` (class-level) | yes — contributes base path |
| `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` | yes |
| `@RequestMapping(method = RequestMethod.X)` (method-level) | yes |
| `@PathVariable` | yes — name + type captured |
| `@RequestParam` (incl. explicit `value` / `name`) | yes |
| `@RequestBody` | detected; marks `hasRequestBody = true` |

## What it deliberately does not see

See [`docs/22-static-discovery-limits.md`](../docs/22-static-discovery-limits.md)
for the five explicit failure modes (JPA derived queries, MyBatis dynamic SQL,
`@Async`/`@Scheduled`, DI-by-interface, reflection dispatch). The R1 risk in
[`graph-rag-test-generator-risks.md`](../graph-rag-test-generator-risks.md) is
mitigated by *making these gaps loud* (Stage 3 strict-mode quarantine + Stage 6
coverage delta) rather than by trying to close them with more analyzer cleverness.

## Output shape

`endpoints.json` — one `Endpoint` per discovered handler. `authRequired` is
always false / `requiredRoles` is always empty; auth handling travels via the
scout config's per-step headers instead.

`paths.json` — for each handler:

- one **happy path** ExploredPath with the boundary generator's default values,
  exit status guessed from HTTP method (`200`, or `201` for POST)
- for each numeric `@PathVariable`, three additional boundary variants
  (`0`, `-1`, `2147483647`) plus an empty-string variant (`""`) that the
  Spring binder rejects with 400

Variants get deterministic slug ids like `static_find_ownerId-neg1` so two runs
on the same source produce byte-identical `paths.json` (R6).

## Acceptance criteria status

- [x] petclinic SUT source → 29 endpoints discovered (≥ 10 required)
- [x] each endpoint gets at least one happy ExploredPath
- [x] AOP-annotated methods stay in the controller class's method list
      (`@Transactional` / `@Async` don't hide them — the scanner ignores any
      annotation it doesn't know about)
- [x] generated `paths.json` round-trips through `JsonMappers.standard()` and
      `ArchiveReader` (locked by `PathDiscoveryStaticTest`)
- [x] same for `endpoints.json`
- [x] boundary generator yields ≥4 variants per numeric param
      (`0`, `-1`, `2147483647`, `""` — plus the happy value)
- [x] failure modes documented in `docs/22-static-discovery-limits.md`

## Determinism

All output is deterministic: file walk order is the JDK's, annotation iteration
order is the parse order, boundary value order is a `LinkedHashSet`. Re-running
on the same source produces the same `paths.json` and `endpoints.json` byte for
byte. This is the **R6 idempotency guarantee** the Stage 6 termination logic
relies on (otherwise the loop never converges).
