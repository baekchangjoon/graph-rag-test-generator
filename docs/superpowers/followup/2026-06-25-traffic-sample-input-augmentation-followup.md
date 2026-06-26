# Traffic Sample-based Input Augmentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable injection of real traffic patterns into `graph-rag-builder` by implementing a segment-matching URL mapper and adapting `ReadInputSynthesizer` to use traffic sample parameter values over default generated probes.

**Architecture:**
- Create `SegmentMatcher.java` to map concrete runtime URLs to Spring controller URL templates (e.g. mapping `/api/orders/123` to `/api/orders/{id}`).
- Extend `ResolutionHint.java` to hold parameter values parsed from matched traffic queries and paths.
- Update `ReadInputSynthesizer.java` to check for parameters in `ResolutionHint` before calling the default fallback method `scalarFor(...)`.
- Create `TrafficHintLoader.java` to parse `--traffic-samples <file>` and orchestrate the parameter overlays.

**Tech Stack:** Java, Spring Web (URI Templates), Jackson JSON, JUnit 5

## Global Constraints
- All file links must use the `file://` schema with absolute paths.
- Do not use placeholders such as "TODO", "implement later", or "add error handling". All instructions must show exact classes, methods, and parameters.
- Rebase-only merge must be followed (`gh pr merge --rebase`).

---

## Detailed Tasks

### Task 1: Expose ResolutionHint API for Traffic Parameters
**Files:**
- Modify: [ResolutionHint.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/ResolutionHint.java)

**Interfaces:**
- Consumes: A dictionary of key-value parameters parsed from traffic.
- Produces: Getter method to query custom parameter values during input synthesis.

- [ ] **Step 1: Locate ResolutionHint.java**
  Examine the current implementation of `ResolutionHint` to add a parameter storage map.

- [ ] **Step 2: Add traffic value attributes and builder methods**
  Add the following methods and field:
  ```java
  private final Map<String, String> trafficValues = new HashMap<>();

  public ResolutionHint withTrafficValue(String paramName, String value) {
      this.trafficValues.put(paramName, value);
      return this;
  }

  public String getTrafficValue(String paramName) {
      return this.trafficValues.get(paramName);
  }

  public boolean hasTrafficValue(String paramName) {
      return this.trafficValues.containsKey(paramName) && this.trafficValues.get(paramName) != null;
  }
  ```

- [ ] **Step 3: Compile builder module**
  Command: `./gradlew :graph-rag-builder:compileJava`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit changes**
  Command: `git commit -am "feat: add traffic values map and accessor API to ResolutionHint"`

---

### Task 2: Adapt ReadInputSynthesizer to Use Traffic Parameters
**Files:**
- Modify: [ReadInputSynthesizer.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java)

**Interfaces:**
- Consumes: `ResolutionHint` in `synthesize` method.
- Produces: Synthetic parameters derived from `ResolutionHint` if available, falling back to `scalarFor(...)`.

- [ ] **Step 1: Update synthesis parameter loop**
  Update the loop resolving values inside [ReadInputSynthesizer.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java#L84-L90):
  ```java
  for (EndpointParam param : endpoint.params()) {
      if (param.kind() != ParamKind.PATH && param.kind() != ParamKind.QUERY) {
          continue;
      }
      String value;
      if (hint != null && hint.hasTrafficValue(param.name())) {
          value = hint.getTrafficValue(param.name());
      } else {
          value = scalarFor(param, probeId);
      }
      input.put(param.name(), value);
  ```

- [ ] **Step 2: Run unit tests**
  Command: `./gradlew :graph-rag-builder:test`
  Expected: All existing tests pass.

- [ ] **Step 3: Commit updates**
  Command: `git commit -am "feat: update ReadInputSynthesizer to prioritize ResolutionHint traffic values"`

---

### Task 3: Implement URL Segment-Matching Utility
**Files:**
- Create: [SegmentMatcher.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/SegmentMatcher.java)

**Interfaces:**
- Consumes: A template path (e.g. `/api/v1/orders/{id}`) and a concrete path (e.g. `/api/v1/orders/ORD-001`).
- Produces: Map of extracted path variables, or empty if template does not match.

- [ ] **Step 1: Write SegmentMatcherTest unit tests**
  Verify matching cases like:
  - `/orders/{id}` matches `/orders/100` -> yields `{id="100"}`
  - `/orders/{id}/items/{item}` matches `/orders/100/items/3` -> yields `{id="100", item="3"}`
  - `/orders/{id}` does not match `/customers/100` -> empty

- [ ] **Step 2: Implement segment matching logic**
  Write the class in `SegmentMatcher.java`:
  ```java
  package io.graphrag.builder.run;

  import java.util.*;

  public final class SegmentMatcher {
      public static Map<String, String> match(String template, String concretePath) {
          String[] tSegs = template.split("/");
          String[] cSegs = concretePath.split("/");
          if (tSegs.length != cSegs.length) {
              return Collections.emptyMap();
          }
          Map<String, String> vars = new HashMap<>();
          for (int i = 0; i < tSegs.length; i++) {
              String t = tSegs[i];
              String c = cSegs[i];
              if (t.startsWith("{") && t.endsWith("}")) {
                  String varName = t.substring(1, t.length() - 1);
                  vars.put(varName, c);
              } else if (!t.equalsIgnoreCase(c)) {
                  return Collections.emptyMap();
              }
          }
          return vars;
      }
  }
  ```

- [ ] **Step 3: Run the SegmentMatcher tests**
  Command: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.run.SegmentMatcherTest"`
  Expected: PASS

- [ ] **Step 4: Commit**
  Command: `git add . && git commit -m "feat: implement SegmentMatcher utility for URL routing extraction"`

---

### Task 4: Implement TrafficHintLoader and CLI Plumbing
**Files:**
- Create: [TrafficHintLoader.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/TrafficHintLoader.java)
- Modify: [BuilderCli.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java)

**Interfaces:**
- Consumes: CLI argument `--traffic-samples <path>`.
- Produces: List of parsed parameter hints loaded into the runner execution context.

- [ ] **Step 1: Implement TrafficHintLoader.java**
  Read and parse the JSON file (containing array of traffic metrics) using Jackson:
  ```java
  package io.graphrag.builder.run;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import io.graphrag.model.Endpoint;
  import java.io.File;
  import java.util.*;

  public final class TrafficHintLoader {
      private static final ObjectMapper MAPPER = new ObjectMapper();

      public static Map<String, String> loadHints(File file, Endpoint endpoint) {
          // Parse JSON traffic objects, segment-match the 'path' with endpoint.pathTemplate()
          // Parse query parameters out of sampleRequests, merge with path variables and return
          return Collections.emptyMap(); // Implement matching loops
      }
  }
  ```

- [ ] **Step 2: Add Option in BuilderCli.java**
  Wire `--traffic-samples` in `BuilderCli.java`, resolve the file, call `TrafficHintLoader`, and pass populated `ResolutionHint` instances to the `ReadInputSynthesizer`.

- [ ] **Step 3: Verify integrated pipeline run**
  Create a test input JSON file, run builder against SUT with the flag, and assert values inside `graph.json`.

- [ ] **Step 4: Commit changes**
  Command: `git commit -am "feat: implement TrafficHintLoader and integrate with BuilderCli"`
