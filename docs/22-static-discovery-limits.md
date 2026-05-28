# Static Path Discovery — Explicit Limits (R1)

`path-discovery-static` is an **AST-only** Spring controller scanner. It does not
run the application, it does not resolve symbols across files, and it does not
read bytecode. The goal is to enumerate REST handlers + boundary-value variants
cheaply enough to feed back into Stage 2 (`scout-step-translator`) and Stage 3
(`scout-launcher`); the downstream `strict-mode` quarantine (T3) and coverage
feedback (T5/T6) are what catch the misses.

This doc spells out the five most common Spring/JPA patterns the scanner cannot
see, why, and what to do about each. If you're reading a Stage 6 report and a
chunk of branches stayed `still_missing`, start here.

---

## 1. JPA derived-query repository methods

```java
public interface OwnerRepository extends JpaRepository<Owner, Integer> {
    List<Owner> findByLastNameStartingWith(String prefix);
    Optional<Owner> findByPhoneNumber(String phone);
}
```

- **What the scanner sees:** an interface, no controller annotation, no
  method body. The static analyzer reports zero handlers and zero SQL.
- **Why:** the actual SQL is synthesized by Spring Data at runtime from the
  method name. No literal SQL exists in the source tree; even a symbol solver
  wouldn't help.
- **Workaround:** Stage 3's `jdbc-intercept-agent` bridge captures the SQL
  *when the live SUT actually executes it*. So an endpoint that hits a
  derived query will get its `captured_sql.json` populated correctly — as long
  as a scout step exercises that endpoint. The static scanner's job is just to
  surface the endpoint exists; the SQL fills itself in at scout time.
- **What goes red:** nothing if the endpoint is reachable from a scout step.
  If the endpoint isn't reachable (e.g. needs a specific request body shape
  to dispatch), Stage 6 lists the missed branches.

## 2. MyBatis dynamic SQL (`<if>` / `<foreach>` / `<choose>`)

```xml
<select id="findActive" resultType="User">
  SELECT * FROM users WHERE deleted = 0
  <if test="role != null">AND role = #{role}</if>
  <foreach collection="ids" item="id" open="AND id IN (" separator="," close=")">
    #{id}
  </foreach>
</select>
```

- **What the scanner sees:** a `@Mapper` interface method, no `@RequestMapping`
  → not a handler. (Indirectly, this is also a problem for `graph-rag-builder`
  fixture synthesis even when scout captures the assembled SQL.)
- **Why:** the SQL shape is computed at runtime from input. There is no single
  "the SQL for this method" that AST could extract.
- **Workaround:** rely on scout-time capture (`captured_sql.json` records the
  *actually-executed* SQL with bindings). A future enhancement could store the
  XML fragment source alongside the captured SQL (`CapturedSql.source_xml`),
  but that's out of T4 scope.
- **What goes red:** nothing for the static scanner specifically. The pain
  surfaces in `graph-rag-builder` `FixtureComposer` when the captured SQL has
  no clean way to reverse into seed `INSERT`s.

## 3. `@Async` / `@Scheduled` methods

```java
@Service
public class NotificationService {
    @Async
    public CompletableFuture<Void> notifyAll(List<User> users) { ... }

    @Scheduled(cron = "0 */5 * * * *")
    public void cleanup() { ... }
}
```

- **What the scanner sees:** a service method, no `@RequestMapping` → not a
  handler. From the scanner's POV these methods are invisible.
- **Why:** these aren't HTTP entry points; they run on a TaskExecutor or
  scheduler thread. They show up in JaCoCo coverage but the scanner has no
  endpoint to attribute them to.
- **Workaround:** none from path-discovery-static — async / scheduled code paths
  must be tested separately (unit tests, manual triggers). T6 final-report.md
  surfaces them as `still_missing` branches in the `not-reachable-via-rest`
  bucket so they get on the manual-review queue instead of being silently
  ignored.

## 4. Spring DI by interface — `@Autowired` of an interface with N implementations

```java
@RestController
public class PaymentController {
    private final PaymentGateway gateway;          // interface
    PaymentController(PaymentGateway gateway) { this.gateway = gateway; }
    @PostMapping("/charge")
    public void charge(@RequestBody ChargeRequest r) { gateway.charge(r); }
}

interface PaymentGateway { void charge(ChargeRequest r); }
@Service class StripeGateway implements PaymentGateway { ... }
@Service class PayPalGateway implements PaymentGateway { ... }
```

- **What the scanner sees:** the controller method, correctly.
- **What it does NOT see:** which concrete `PaymentGateway` implementation
  will be wired in at runtime, and therefore which branches inside `charge()`
  are reachable. So `branches_taken` in `paths.json` only covers the
  controller's own statements.
- **Why:** Spring DI binding is runtime — qualifier annotations, `@Primary`,
  conditional configs, profile activation all affect it.
- **Workaround:** the scanner intentionally does not try to follow into
  injected dependencies. The scout captures the *actually-taken* path via JaCoCo
  at test time, and T5's coverage delta compares predicted vs actual.
- **What goes red:** Stage 6 reports a "predicted branches differ from actual"
  delta; cluster of such deltas concentrated in interface methods is a strong
  signal of DI-by-interface uncertainty.

## 5. Reflection-based dispatch

```java
@PostMapping("/handle/{type}")
public Object handle(@PathVariable String type, @RequestBody Map<String,Object> body)
        throws Exception {
    Class<?> cls = Class.forName("com.example.handlers." + type);
    Handler h = (Handler) cls.getDeclaredConstructor().newInstance();
    return h.process(body);
}
```

- **What the scanner sees:** the `handle()` method itself.
- **What it does NOT see:** which `Handler` subclasses might be dispatched to.
  The scanner has no chance — even reading `Class.forName(...)` as a constant
  would require symbol resolution we deliberately skipped.
- **Why:** reflection by definition resolves at runtime.
- **Workaround:** none from path-discovery-static. T1's manual-archive seed
  pattern is the escape hatch — author the missing `ExploredPath`s by hand for
  the known reflective dispatch targets, then run scout normally. The R2
  path-id preservation means a hand-authored path joins back to scout-captured
  SQL the same as an analyzer-emitted one.

---

## What to do when you see `still_missing` from Stage 6

1. **Look at the source location.** If it's inside a JPA repository or
   `@Mapper` interface → case 1 or 2; the SQL is captured but maybe the
   triggering endpoint isn't being exercised. Add a scout step.
2. **If the source is an `@Async` / `@Scheduled` method** → case 3; this code
   needs unit-test coverage outside the REST surface.
3. **If the source is inside a method that takes an interface parameter** →
   case 4; pick a concrete implementation to instantiate explicitly in a
   manual `ExploredPath` (T1 pattern).
4. **If the source is dispatched via reflection** → case 5; same — hand-author
   a path per concrete target.
5. **Otherwise** the scanner has a bug; please add the failing controller
   shape to `path-discovery-static/src/test/resources/sample-controllers/`.

## Out of scope (not yet)

- OpenAPI spec ingestion (`--openapi-spec` flag) for cross-checking discovered
  endpoints against documented contracts. Workorder T4 mentions it as
  optional input; deferred to a later iteration once we have signal from real
  SUTs that it pays off.
- Spring Security analysis to populate `Endpoint.authRequired` /
  `requiredRoles`. Currently always false / empty. Out-of-band auth headers
  travel via the scout config's per-step headers.
- Reflection / `Class.forName` constant-folding — too much complexity per
  edge case caught.
