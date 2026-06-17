# Builder `--endpoint` Selector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `--endpoint <spec[,spec]>` option to `graph-rag-builder` that explores ONLY the selected unit(s) — by endpoint id (`post-api-orders`) or `METHOD /path` (`POST /api/orders`) — instead of all endpoints. With `--incremental-base`, the rest is carried over from the base graph (full graph, refreshed subset); without a base, a partial graph (only the selected units) is produced.

**Architecture:** Reuse the existing incremental machinery. `IncrementalPlan` already gates every explorable unit (HTTP endpoint / WS endpoint / Kafka consumer) via `shouldExplore(id)` and carries over non-explored facts. We add: (1) an `EndpointSelector` that resolves user specs to concrete unit ids against the static index, (2) a `planForEndpoints(...)` factory that sets `exploreIds` to the selected ids and carries over everyone else from the base, and (3) CLI wiring. The generator is unchanged — it is already strictly per-endpoint (`GenerationRequest.endpointId`).

**Tech Stack:** Java 17, Gradle, JUnit 5, existing `IncrementalPlan`/`IncrementalBuildPlanner`/`BuilderCli`.

---

## v2 — 3-model review triage (2026-06-17)

Reviewed by Sonnet/Gemini/GPT, grounded in the repo. All findings accepted (no rejections):
- **[CRITICAL] `KafkaExchange.consumerId()` does not exist** — real accessor is **`kafkaConsumerId()`** (verified `KafkaExchange(id, kafkaConsumerId, topic, payload, capturedSqlIds, variant)`). Fixed in Task 2.
- **[CRITICAL] Kafka SQL carry-over** — Kafka SQL is linked via `KafkaExchange.capturedSqlIds()` (list of SQL ids), NOT `CapturedSql.pathId`. The `pathId`-only filter would silently drop Kafka SQL. Fixed: carry SQL whose id ∈ carried exchanges' `capturedSqlIds`.
- **[CRITICAL, GPT] `RequiredSeed` carry-over missing** — `GraphAsset` has a `seeds` component (`RequiredSeed(id, pathId, table, columns, values)`); carried read/by-id paths keep `requiredSeedIds` but their seed rows would vanish in merge mode → broken generated fixtures. Fixed: add `carriedSeeds`. (Note: existing `IncrementalBuildPlanner.plan` *also* doesn't carry seeds — a pre-existing gap; we add the field with empty default there to preserve current behavior and only fill it in `planForEndpoints`. Fixing existing incremental is out of this scope.)
- **[important] `BuildConfig` delegating constructors** — `BuildConfig` is a record with **two** convenience overloads (lines ~39 and ~49) that `this(...)`-delegate to the canonical ctor; both must pass `List.of()` for the new `endpointSelectors`.
- **[important] partial-graph contract** — define it: `--endpoint` scopes **exploration facts** (paths/sql/httpCalls/wsExchanges/kafkaExchanges/seeds), NOT the static unit metadata. `GraphAsset.endpoints()/wsEndpoints()/kafkaConsumers()` keep the **full static index** (cheap, source-derived, lets the generator know what exists). Acceptance asserts both: `paths()` scoped to selected; `endpoints()` still full.
- **[important] WS/Kafka selection untested** — add `EndpointSelector` + `planForEndpoints` unit coverage for WS endpoint ids and Kafka consumer ids (selected + carried).
- **[important] acceptance gate** — `BuilderEndpointSelectorTest` needs `@Tag("integration")` + `@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")` (mirroring `BuilderE2eTest`), reading `sut.src`/`sut.jar` system props, with a concrete sample endpoint.
- **[recommended] empty `--endpoint`** — `--endpoint ""`/`--endpoint ","` must NOT silently fall back to full build; track presence separately and error if present-but-no-specs.
- **[recommended] clarity** — name the two `new IncrementalPlan(...)` call-sites (`exploreAll()` line 28, `plan()` return line 61); note `wsIndex`/`kafkaIndex` are in scope at the plan-selection block.

---

## Scope & decisions (from brainstorming, user-approved)

- Identifier: **both** — exact `endpoint.id()` match first, else parse `METHOD /path` → matching HTTP endpoint's id. No match → error listing candidates.
- Multiple: **comma-separated list** `--endpoint a,b`.
- Applies to **HTTP endpoints, WS endpoints, Kafka consumers** (all share `shouldExplore(id)`). (Only HTTP units are resolvable by `METHOD /path`; WS/Kafka are resolvable by id.)
- Graph output (Q2=C): `--incremental-base` present → **merge** (carry over the rest from base); absent → **partial graph** (only selected), logged as partial.
- `--endpoint` takes **precedence over `--changed-files`** (explicit filter wins; if both present, use `--endpoint` and log a warning).
- **Kafka carry-over gap fix:** the existing incremental carry-over covers endpoints + WS but NOT Kafka exchanges. To make `--endpoint` merge mode correct on Kafka-bearing SUTs, add `carriedKafkaExchanges` to `IncrementalPlan` (empty default → existing `exploreAll()`/`IncrementalBuildPlanner.plan` behavior unchanged) and carry Kafka over in `planForEndpoints`.

## E2E / acceptance (definition of done)

- **Acceptance (integration, Testcontainers + real SUT process — the highest feasible out-of-process level for the builder):** a `BuilderEndpointSelectorTest` that runs `BuilderCli.build(config)` against the `graph-rag-builder` test sample SUT (same harness as `BuilderE2eTest`) with `endpointSelectors=["<one endpoint>"]` and **no base**, asserting the resulting `GraphAsset.paths()` contains paths for ONLY that endpoint (others absent). A second case with a base `GraphAsset` asserts the full set is present with the selected endpoint's paths refreshed and the rest carried over verbatim.
- **Unit:** `EndpointSelector` (id match, `METHOD /path` match, no-match error + candidate list, multi-spec); `planForEndpoints` (exploreIds = selected; carried = the rest incl. Kafka; no-base = empty carried).
- **Regression:** `./gradlew :graph-rag-builder:test` green; existing `BuilderE2eTest`/`IncrementalBuildPlannerTest` unchanged-green (proves `exploreAll`/incremental behavior preserved).

---

## File Structure

- Create `graph-rag-builder/src/main/java/io/graphrag/builder/cli/EndpointSelector.java` — resolve specs → unit ids.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/cli/IncrementalPlan.java` — add `carriedKafkaExchanges` + `carriedSeeds` components (+ keep `exploreAll()` default empty).
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/cli/IncrementalBuildPlanner.java` — add `planForEndpoints(...)`; keep existing `plan(...)` carrying empty Kafka (behavior preserved).
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` — `--endpoint` parse; `BuildConfig.endpointSelectors`; plan selection + precedence; partial-graph log; `kafkaExchanges.addAll(plan.carriedKafkaExchanges())`.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java` — add `List<String> endpointSelectors` (+ overload default `List.of()`).
- Test: `EndpointSelectorTest`, `IncrementalBuildPlannerEndpointTest`, `BuilderEndpointSelectorTest`.
- Docs: `README.md` (builder usage example), `docs/03-graph-rag-builder.md` (short note).

---

### Task 1: `EndpointSelector` — resolve specs to unit ids

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/EndpointSelector.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/EndpointSelectorTest.java`

`Endpoint` has `id()`, `httpMethod()`, `path()`. WS units (`WsEndpoint`) and Kafka units (`KafkaConsumer`) have `id()`. A spec matches if: it equals a known unit id; OR it parses as `METHOD /path` (case-insensitive method, single space) and equals an HTTP endpoint's `httpMethod() + " " + path()`. Resolution returns the union of matched ids (LinkedHashSet, input order). Any unmatched spec → `IllegalArgumentException` listing the unmatched spec and the available ids + `METHOD path` forms.

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.builder.cli;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EndpointSelectorTest {
    private final Endpoint orders = new Endpoint("post-api-orders", "POST", "/api/orders",
            "io.x.OrderController", "create", List.of(), false);
    private final Endpoint getOrder = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}",
            "io.x.OrderController", "get", List.of(), false);

    @Test void matchesById() {
        var ids = EndpointSelector.resolve(List.of("post-api-orders"),
                List.of(orders, getOrder), List.of(), List.of());
        assertEquals(java.util.Set.of("post-api-orders"), ids);
    }
    @Test void matchesByMethodAndPath() {
        var ids = EndpointSelector.resolve(List.of("GET /api/orders/{id}"),
                List.of(orders, getOrder), List.of(), List.of());
        assertEquals(java.util.Set.of("get-api-orders-id"), ids);
    }
    @Test void multipleSpecs() {
        var ids = EndpointSelector.resolve(List.of("post-api-orders", "GET /api/orders/{id}"),
                List.of(orders, getOrder), List.of(), List.of());
        assertEquals(2, ids.size());
    }
    @Test void noMatchThrowsWithCandidates() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                EndpointSelector.resolve(List.of("POST /nope"),
                        List.of(orders), List.of(), List.of()));
        assertTrue(ex.getMessage().contains("POST /nope"));
        assertTrue(ex.getMessage().contains("post-api-orders"));   // candidate listed
    }
}
```

(Match the real `Endpoint` constructor arity — read `shared-model/.../Endpoint.java` first and adjust the test's `new Endpoint(...)` to the actual record components; the test above assumes `(id, httpMethod, path, handlerClass, handlerMethod, params, authRequired)`. If different, fix the test constructor calls accordingly — the selector only uses `id()`, `httpMethod()`, `path()`.)

Also add a case **`resolvesWsAndKafkaById`**: pass `WsEndpoint`/`KafkaConsumer` (read their record arities) with known ids into `resolve(List.of("<ws-id>", "<kafka-id>"), List.of(), List.of(wsEndpoint), List.of(kafkaConsumer))` and assert both ids resolve (WS/Kafka are id-only; `METHOD /path` matching is HTTP-only).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests '*EndpointSelectorTest'`
Expected: FAIL (class missing).

- [ ] **Step 3: Implement**

```java
package io.graphrag.builder.cli;

import io.graphrag.model.Endpoint;
import io.graphrag.model.KafkaConsumer;
import io.graphrag.model.WsEndpoint;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** --endpoint 스펙(id 또는 "METHOD /path")을 탐색 단위 id로 해석. 미매칭 시 후보와 함께 실패. */
public final class EndpointSelector {

    private EndpointSelector() {}

    public static Set<String> resolve(List<String> specs, List<Endpoint> endpoints,
                                      List<WsEndpoint> wsEndpoints, List<KafkaConsumer> kafkaConsumers) {
        Set<String> ids = new LinkedHashSet<>();
        endpoints.forEach(e -> ids.add(e.id()));
        wsEndpoints.forEach(w -> ids.add(w.id()));
        kafkaConsumers.forEach(k -> ids.add(k.id()));

        Set<String> resolved = new LinkedHashSet<>();
        for (String raw : specs) {
            String spec = raw.strip();
            if (spec.isEmpty()) { continue; }
            if (ids.contains(spec)) { resolved.add(spec); continue; }
            String byMethodPath = matchMethodPath(spec, endpoints);
            if (byMethodPath != null) { resolved.add(byMethodPath); continue; }
            throw new IllegalArgumentException(
                    "no explorable unit matches --endpoint '" + spec + "'. candidates: "
                            + candidates(endpoints, wsEndpoints, kafkaConsumers));
        }
        return resolved;
    }

    /** "METHOD /path" → 일치하는 HTTP endpoint id (대소문자 무시 method). 없으면 null. */
    private static String matchMethodPath(String spec, List<Endpoint> endpoints) {
        int sp = spec.indexOf(' ');
        if (sp <= 0) { return null; }
        String method = spec.substring(0, sp).strip();
        String path = spec.substring(sp + 1).strip();
        for (Endpoint e : endpoints) {
            if (e.httpMethod().equalsIgnoreCase(method) && e.path().equals(path)) {
                return e.id();
            }
        }
        return null;
    }

    private static String candidates(List<Endpoint> endpoints, List<WsEndpoint> wsEndpoints,
                                     List<KafkaConsumer> kafkaConsumers) {
        List<String> lines = new java.util.ArrayList<>();
        endpoints.forEach(e -> lines.add(e.id() + " (" + e.httpMethod() + " " + e.path() + ")"));
        wsEndpoints.forEach(w -> lines.add(w.id() + " (ws)"));
        kafkaConsumers.forEach(k -> lines.add(k.id() + " (kafka)"));
        return String.join(", ", lines);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :graph-rag-builder:test --tests '*EndpointSelectorTest'`
Expected: PASS.

- [ ] **Step 5: Commit** `feat(builder): EndpointSelector resolve specs (id | METHOD /path)`

---

### Task 2: `IncrementalPlan.carriedKafkaExchanges` + `IncrementalBuildPlanner.planForEndpoints`

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/IncrementalPlan.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/IncrementalBuildPlanner.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/IncrementalBuildPlannerEndpointTest.java`

- [ ] **Step 1: Add `carriedKafkaExchanges` + `carriedSeeds` to `IncrementalPlan` (behavior-preserving)**

Change the record to add TWO trailing components and update `exploreAll()`:

```java
public record IncrementalPlan(
        Set<String> exploreIds,
        List<ExploredPath> carriedPaths,
        List<CapturedSql> carriedSql,
        List<CapturedHttpCall> carriedHttpCalls,
        List<WsExchange> carriedWsExchanges,
        List<io.graphrag.model.KafkaExchange> carriedKafkaExchanges,
        List<io.graphrag.model.RequiredSeed> carriedSeeds) {

    public boolean shouldExplore(String id) {
        return exploreIds == null || exploreIds.contains(id);
    }

    public static IncrementalPlan exploreAll() {
        return new IncrementalPlan(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
```

There are exactly **two** `new IncrementalPlan(...)` call-sites: `IncrementalPlan.exploreAll()` (line 28, shown above) and `IncrementalBuildPlanner.plan(...)`'s return (line 61). Update the `plan(...)` return to pass `List.of(), List.of()` for the two new trailing args (Kafka/seeds still not carried in changed-files mode — existing behavior preserved). `IncrementalBuildPlannerTest` constructs `IncrementalPlan` only via `exploreAll()`/`plan(...)` (not `new`), so it does not break on the new arity.

- [ ] **Step 2: Write the failing test for `planForEndpoints`**

```java
package io.graphrag.builder.cli;

import io.graphrag.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalBuildPlannerEndpointTest {
    // minimal endpoints/paths; adjust record constructors to real arities when implementing
    @Test void exploresOnlySelectedAndCarriesRestInclKafkaAndSeeds() {
        Endpoint a = endpoint("ep-a"); Endpoint b = endpoint("ep-b");
        // pb (carried) references a seed via requiredSeedIds; seed row must be carried too
        ExploredPath pa = path("p-a", "ep-a", List.of());
        ExploredPath pb = path("p-b", "ep-b", List.of("seed-b"));
        RequiredSeed seedB = new RequiredSeed("seed-b", "p-b", "t", List.of("id"), List.of("1"));
        // kafka exchange (carried) owns SQL via capturedSqlIds (NOT pathId)
        KafkaExchange kx = kafka("kx-1", "kc-1", List.of("ksql-1"));
        CapturedSql ksql = capturedSql("ksql-1", null);   // pathId null — linked only via capturedSqlIds
        GraphAsset base = asset(List.of(a, b), List.of(pa, pb),
                List.of(kx), List.of(ksql), List.of(seedB));

        IncrementalPlan plan = new IncrementalBuildPlanner().planForEndpoints(
                base, Set.of("ep-a"), List.of(a, b), List.of(),
                List.of(/* no kafka consumer in exploreIds → carried */ kafkaConsumer("kc-1")));

        assertTrue(plan.shouldExplore("ep-a"));
        assertFalse(plan.shouldExplore("ep-b"));
        assertTrue(plan.carriedPaths().stream().anyMatch(p -> p.id().equals("p-b")));
        assertFalse(plan.carriedPaths().stream().anyMatch(p -> p.id().equals("p-a")));
        assertEquals(1, plan.carriedKafkaExchanges().size());
        // Kafka SQL carried via capturedSqlIds, not pathId
        assertTrue(plan.carriedSql().stream().anyMatch(s -> s.id().equals("ksql-1")));
        // RequiredSeed for carried path carried too
        assertTrue(plan.carriedSeeds().stream().anyMatch(s -> s.id().equals("seed-b")));
    }

    @Test void noBaseProducesEmptyCarry() {
        IncrementalPlan plan = new IncrementalBuildPlanner().planForEndpoints(
                null, Set.of("ep-a"), List.of(endpoint("ep-a")), List.of(), List.of());
        assertTrue(plan.shouldExplore("ep-a"));
        assertTrue(plan.carriedPaths().isEmpty() && plan.carriedSeeds().isEmpty()
                && plan.carriedKafkaExchanges().isEmpty());
    }
    // helper factories endpoint(id)/path(id,endpointId,requiredSeedIds)/kafka(id,consumerId,sqlIds)/
    // kafkaConsumer(id)/capturedSql(id,pathId)/asset(endpoints,paths,kafkaExch,sql,seeds) per real arities
}
```

(Read `shared-model` records `Endpoint`, `ExploredPath`, `KafkaExchange`, `KafkaConsumer`, `CapturedSql`, `RequiredSeed`, `GraphAsset` to write the helper factories with correct arities. Also add an `EndpointSelector` test for a WS endpoint id and a Kafka consumer id resolving by id, in `EndpointSelectorTest`.)

- [ ] **Step 3: Implement `planForEndpoints`**

Add to `IncrementalBuildPlanner`. Note the two distinct SQL linkage styles (verified against the model): HTTP/WS SQL links via `CapturedSql.pathId` == path/exchange id; **Kafka SQL links via `KafkaExchange.capturedSqlIds()`** (a list of SQL ids), NOT `pathId`. Seeds link via `RequiredSeed.pathId` and/or `ExploredPath.requiredSeedIds()`.

```java
    /** --endpoint 전용: 주어진 id 집합만 탐색, 나머지는 base에서 전부 이월(Kafka·seed 포함). base=null이면 부분(이월 없음). */
    public IncrementalPlan planForEndpoints(GraphAsset previous, Set<String> exploreIds,
            List<Endpoint> endpoints, List<WsEndpoint> wsEndpoints,
            List<io.graphrag.model.KafkaConsumer> kafkaConsumers) {
        if (previous == null) {
            return new IncrementalPlan(new java.util.LinkedHashSet<>(exploreIds),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        // carried unit ids = present in current index AND not selected
        Set<String> carriedEndpointIds = new java.util.LinkedHashSet<>();
        endpoints.forEach(e -> { if (!exploreIds.contains(e.id())) carriedEndpointIds.add(e.id()); });
        Set<String> carriedWsIds = new java.util.LinkedHashSet<>();
        wsEndpoints.forEach(w -> { if (!exploreIds.contains(w.id())) carriedWsIds.add(w.id()); });
        Set<String> carriedKafkaConsumerIds = new java.util.LinkedHashSet<>();
        kafkaConsumers.forEach(k -> { if (!exploreIds.contains(k.id())) carriedKafkaConsumerIds.add(k.id()); });

        List<ExploredPath> carriedPaths = previous.paths().stream()
                .filter(p -> carriedEndpointIds.contains(p.endpointId())).toList();
        List<WsExchange> carriedWs = previous.wsExchanges().stream()
                .filter(x -> carriedWsIds.contains(x.wsEndpointId())).toList();
        List<io.graphrag.model.KafkaExchange> carriedKafka = previous.kafkaExchanges().stream()
                .filter(x -> carriedKafkaConsumerIds.contains(x.kafkaConsumerId())).toList();

        // SQL: pathId join for HTTP/WS; explicit capturedSqlIds for Kafka.
        Set<String> carriedPathIds = new java.util.LinkedHashSet<>();
        carriedPaths.forEach(p -> carriedPathIds.add(p.id()));
        carriedWs.forEach(x -> carriedPathIds.add(x.id()));
        Set<String> carriedKafkaSqlIds = new java.util.LinkedHashSet<>();
        carriedKafka.forEach(x -> carriedKafkaSqlIds.addAll(x.capturedSqlIds()));
        List<CapturedSql> carriedSql = previous.sql().stream()
                .filter(s -> carriedPathIds.contains(s.pathId()) || carriedKafkaSqlIds.contains(s.id()))
                .toList();

        // Seeds: by RequiredSeed.pathId == carried path id, OR id referenced by a carried path's requiredSeedIds.
        Set<String> carriedSeedRefIds = new java.util.LinkedHashSet<>();
        carriedPaths.forEach(p -> carriedSeedRefIds.addAll(p.requiredSeedIds()));
        List<io.graphrag.model.RequiredSeed> carriedSeeds = previous.seeds().stream()
                .filter(s -> carriedPathIds.contains(s.pathId()) || carriedSeedRefIds.contains(s.id()))
                .toList();

        return new IncrementalPlan(new java.util.LinkedHashSet<>(exploreIds), carriedPaths,
                carriedSql,
                previous.httpCalls().stream().filter(c -> carriedPathIds.contains(c.pathId())).toList(),
                carriedWs, carriedKafka, carriedSeeds);
    }
```

(Verified getter names against the model: `ExploredPath.endpointId()/id()/requiredSeedIds()`, `WsExchange.wsEndpointId()/id()`, `KafkaExchange.kafkaConsumerId()/capturedSqlIds()`, `CapturedSql.pathId()/id()`, `CapturedHttpCall.pathId()`, `RequiredSeed.pathId()/id()`, `GraphAsset.kafkaExchanges()/seeds()`.)

- [ ] **Step 4: Run tests** `./gradlew :graph-rag-builder:test --tests '*IncrementalBuildPlanner*'` → PASS (new + existing).

- [ ] **Step 5: Commit** `feat(builder): planForEndpoints + carry Kafka exchanges in IncrementalPlan`

---

### Task 3: `BuilderCli` / `BuildConfig` wiring

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`

- [ ] **Step 1: `BuildConfig` gains `List<String> endpointSelectors`**

`BuildConfig` is a record with **two** convenience overload constructors (≈ lines 39 and 49) that `this(...)`-delegate to the canonical constructor. Add `List<String> endpointSelectors` as a new trailing component on the canonical record header, and update **both** existing overloads' `this(...)` delegations to pass `List.of()` for it (so existing `new BuildConfig(...)` call-sites — `BuilderE2eTest` uses one of these overloads — keep compiling). Add accessor `endpointSelectors()` (record gives it free). The canonical compact constructor (`public BuildConfig {`) may normalize `endpointSelectors = endpointSelectors == null ? List.of() : endpointSelectors;`. `main()` uses the full canonical ctor.

- [ ] **Step 2: Parse `--endpoint` in `main()` (empty → error, not silent full build)**

```java
        List<String> endpointSelectors = List.of();
        if (options.containsKey("--endpoint")) {
            endpointSelectors = java.util.Arrays.stream(options.get("--endpoint").split(","))
                    .map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (endpointSelectors.isEmpty()) {
                throw new IllegalArgumentException("--endpoint given but no non-blank spec(s) provided");
            }
        }
```

Pass into `BuildConfig`.

- [ ] **Step 3: Plan selection in `build()`** (replaces/augments the existing incremental block)

Where `build()` currently computes `IncrementalPlan plan` (note: `wsIndex` and `kafkaIndex` are already computed above this block in `build()` — they are in scope here):

```java
        IncrementalPlan plan = IncrementalPlan.exploreAll();
        if (!config.endpointSelectors().isEmpty()) {
            Set<String> ids = EndpointSelector.resolve(config.endpointSelectors(),
                    index.endpoints(), wsIndex.endpoints(), kafkaIndex.consumers());
            GraphAsset base = config.incrementalBase() != null
                    ? new JsonFileGraphStore(config.incrementalBase()).load() : null;
            plan = new IncrementalBuildPlanner().planForEndpoints(base, ids,
                    index.endpoints(), wsIndex.endpoints(), kafkaIndex.consumers());
            if (config.changedFiles() != null && !config.changedFiles().isEmpty()) {
                log.warn("--endpoint overrides --changed-files (explicit endpoint selection)");
            }
            if (base == null) {
                log.warn("partial graph: only endpoint(s) {} explored (no --incremental-base)", ids);
            } else {
                log.info("endpoint selection: re-explore {}, carry over the rest from base", ids);
            }
        } else if (config.incrementalBase() != null) {
            // ... existing incremental block unchanged ...
        }
```

- [ ] **Step 4: Carry Kafka exchanges + seeds**

Where `build()` does the carry-over after the exploration loop (`paths.addAll(plan.carriedPaths()); sql.addAll(plan.carriedSql()); ...`), add:

```java
        kafkaExchanges.addAll(plan.carriedKafkaExchanges());
        allSeeds.addAll(plan.carriedSeeds());
```

(`allSeeds` is the accumulator that feeds `GraphAsset.seeds()`.) **Partial-graph contract:** do NOT filter the static unit lists — `GraphAsset` is still built with the full `index.endpoints()`/`wsIndex.endpoints()`/`kafkaIndex.consumers()` (source-derived, cheap). `--endpoint` scopes only the exploration facts (paths/sql/httpCalls/wsExchanges/kafkaExchanges/seeds). So a partial graph = "all known units listed, only selected unit(s) explored".

- [ ] **Step 5: Compile + run full builder tests** `./gradlew :graph-rag-builder:test` → PASS (analysis-mode/incremental unchanged).

- [ ] **Step 6: Commit** `feat(builder): --endpoint selector wiring (partial graph or carry-over merge)`

---

### Task 4: Acceptance test + docs

**Files:**
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderEndpointSelectorTest.java`
- Modify: `README.md`, `docs/03-graph-rag-builder.md`

- [ ] **Step 1: Acceptance test (mirror `BuilderE2eTest` harness + gate)**

Read `BuilderE2eTest` first to copy its exact gating + setup. Add `BuilderEndpointSelectorTest` (or a case inside `BuilderE2eTest`) with the **same gate**: `@org.junit.jupiter.api.Tag("integration")` and `@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sut.jar", matches = ".+")`, reading `System.getProperty("sut.src")`/`"sut.jar")`/`"external.stubs"` as the existing test does. Build with `endpointSelectors = List.of("<a real sample endpoint — e.g. POST /api/orders>")` and **no base**, then assert on the produced `GraphAsset`:
- **scoped facts:** every `path.endpointId()` equals the selected endpoint id (no other endpoints' paths present);
- **contract — static metadata NOT filtered:** `graph.endpoints()` still contains the full set (more than one), confirming `--endpoint` scopes exploration, not the index.

(If practical, add a second case with a base `GraphAsset` from a prior full build asserting the carried endpoints' paths are present verbatim and the selected one is refreshed. If a full prior build in-test is too heavy, cover the carry-over at the `planForEndpoints` unit level (Task 2) and keep this integration case to the no-base scoping assertion.)

- [ ] **Step 2: Run** `./gradlew :graph-rag-builder:test --tests '*BuilderEndpointSelector*'` (or the augmented `BuilderE2eTest`) → PASS.

- [ ] **Step 3: Docs** — `README.md` "도구 1 단독" block: add an example
  `--args="build --sut-src <src> --sut-jar <jar> --sut-compose <c> --out <dir> --endpoint 'POST /api/orders'"`
  with a one-line note (id 또는 `METHOD /path`; `--incremental-base` 동반 시 나머지 이월, 없으면 부분 그래프).
  `docs/03-graph-rag-builder.md`: a short bullet under the builder usage/incremental section.

- [ ] **Step 4: Commit** `test(builder): endpoint-selection acceptance + docs`

---

## Final verification

- [ ] `./gradlew :graph-rag-builder:test` green (unit + acceptance).
- [ ] `./gradlew check` green (no cross-module regression; `BuildConfig`/`IncrementalPlan` arity changes swept).
- [ ] Spec-compliance + code-quality review (`pr-review-toolkit:code-reviewer`) triaged.
- [ ] README/docs updated on the same branch.

## Self-review notes

- **Spec coverage:** identifier id+METHOD/path (incl. WS/Kafka by id) → Task 1; partial vs merge + Kafka/seed carry → Task 2/3; precedence + empty-selector + logs → Task 3; acceptance (scoping + static-metadata contract) + docs → Task 4.
- **Type consistency (verified against the model):** `IncrementalPlan` gains TWO trailing components (`carriedKafkaExchanges`, `carriedSeeds`); `exploreAll()` (line 28) and `IncrementalBuildPlanner.plan` (line 61 return) pass `List.of(), List.of()` (existing behavior preserved); `planForEndpoints` is the only producer that fills them. Kafka SQL carried via `KafkaExchange.capturedSqlIds()` (not `pathId`); seeds via `RequiredSeed.pathId`/`ExploredPath.requiredSeedIds()`. `KafkaExchange.kafkaConsumerId()` (not `consumerId`). `BuildConfig` adds `endpointSelectors` to the canonical ctor + both convenience overloads pass `List.of()`.
- **Partial-graph contract:** static unit lists (`endpoints()/wsEndpoints()/kafkaConsumers()`) stay full; only exploration facts are scoped. Asserted in the acceptance test.
- **Residual notes:** existing `IncrementalBuildPlanner.plan` (changed-files mode) still does not carry Kafka exchanges or seeds — a pre-existing gap left unchanged (out of scope); only `planForEndpoints` carries them.
