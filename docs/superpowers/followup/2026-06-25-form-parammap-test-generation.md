# Form / ParamMap Endpoint Test Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable REST Assured test generation for form-urlencoded endpoints (`@ModelAttribute` / form params) in the test generator by unlocking the FORM param rejection gate and updating Mustache templates to output `.formParam(...)` fields.

**Architecture:**
- Remove the FORM param check block inside [Generator.java](file:///root/graph-rag-test-generator/test-generator/src/main/java/io/graphrag/generator/Generator.java).
- Extract and flatten fields from `path.sampleInput()` into standard Spring MVC dot-path flat representation (e.g. `user.name=value`) in [Generator.java](file:///root/graph-rag-test-generator/test-generator/src/main/java/io/graphrag/generator/Generator.java).
- Update the Mustache template [test-class.mustache](file:///root/graph-rag-test-generator/test-generator/src/main/resources/templates/test-class.mustache) to check `isForm` and render `.formParam(...)` statements instead of `.body(...)`.

**Tech Stack:** Java, Mustache, REST Assured, JUnit 5

## Global Constraints
- All file links must use the `file://` schema with absolute paths.
- Do not use placeholders such as "TODO", "implement later", or "add error handling". All instructions must show exact classes, methods, and parameters.
- Rebase-only merge must be followed (`gh pr merge --rebase`).

---

## Detailed Tasks

### Task 1: Remove Reject Gate for Form Endpoints
**Files:**
- Modify: [Generator.java](file:///root/graph-rag-test-generator/test-generator/src/main/java/io/graphrag/generator/Generator.java)

**Interfaces:**
- Consumes: `Endpoint` parameter containing `ParamKind.FORM`.
- Produces: Proceeding with normal test generation paths instead of immediately returning a rejection `GenerationResult`.

- [ ] **Step 1: Locate and remove the reject block**
  In [Generator.java](file:///root/graph-rag-test-generator/test-generator/src/main/java/io/graphrag/generator/Generator.java#L69-L73), remove the following block:
  ```java
  if (endpoint.params().stream().anyMatch(p -> p.kind() == io.graphrag.model.ParamKind.FORM)) {
      return new GenerationResult(List.of(),
              List.of("form endpoint not generated (coverage-only): " + endpoint.id()),
              new io.graphrag.model.ParallelSafetyReport(List.of(), List.of()));
  }
  ```

- [ ] **Step 2: Compile the project**
  Command: `./gradlew :test-generator:compileJava`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit the removal**
  Command: `git commit -am "feat: remove FORM parameter endpoint rejection gate in test generator"`

---

### Task 2: Flatten JSON Input into Form Parameters
**Files:**
- Modify: [Generator.java](file:///root/graph-rag-test-generator/test-generator/src/main/java/io/graphrag/generator/Generator.java)

**Interfaces:**
- Consumes: `path.sampleInput()` (as `JsonNode`).
- Produces: Flat list of name-value pairs mapping to Spring's nested field binding format (e.g. `address.city`).

- [ ] **Step 1: Add helper method for flattening JSON**
  Add a recursive helper method in `Generator.java`:
  ```java
  private static void flattenJson(String prefix, JsonNode node, Map<String, String> out) {
      if (node.isObject()) {
          node.fields().forEachRemaining(entry -> {
              String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
              flattenJson(path, entry.getValue(), out);
          });
      } else if (node.isArray()) {
          for (int i = 0; i < node.size(); i++) {
              flattenJson(prefix + "[" + i + "]", node.get(i), out);
          }
      } else if (!node.isNull()) {
          out.put(prefix, node.asText());
      }
  }
  ```

- [ ] **Step 2: Update ScenarioMethod representation**
  Update the `ScenarioMethod` record and `buildScenarioMethod` to support Form Params:
  Add fields `boolean isForm` and `List<Map<String, String>> formFields` to `ScenarioMethod`.
  Inside `buildScenarioMethod`:
  ```java
  boolean isForm = endpoint.params().stream().anyMatch(p -> p.kind() == ParamKind.FORM);
  List<Map<String, String>> formFields = new ArrayList<>();
  if (isForm && path.sampleInput() != null) {
      Map<String, String> flat = new LinkedHashMap<>();
      flattenJson("", path.sampleInput(), flat);
      flat.forEach((k, v) -> {
          Map<String, String> field = new HashMap<>();
          field.put("name", k);
          field.put("valueExpr", "\"" + v.replace("\"", "\\\"") + "\""); // Simple serialization for form fields
          formFields.add(field);
      });
  }
  ```

- [ ] **Step 3: Wire fields into Mustache scope mapping**
  In `renderTestClass` where `methods` are mapped to scope, ensure `isForm` and `formFields` are added to the mustache context map.

- [ ] **Step 4: Compile test generator**
  Command: `./gradlew :test-generator:build`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit changes**
  Command: `git commit -am "feat: implement flat JSON parsing for FORM parameter mapping"`

---

### Task 3: Update Mustache Template for Form Rendering
**Files:**
- Modify: [test-class.mustache](file:///root/graph-rag-test-generator/test-generator/src/main/resources/templates/test-class.mustache)

- [ ] **Step 1: Edit the template file**
  Update the HTTP call chain around [line 36-39](file:///root/graph-rag-test-generator/test-generator/src/main/resources/templates/test-class.mustache#L36-L39):
  ```mustache
  {{{mocksBlock}}}        {{#postCreateCleanup}}io.restassured.response.Response __resp = {{/postCreateCleanup}}{{#authRequired}}scope.rest().authenticated(){{/authRequired}}{{^authRequired}}scope.rest().given(){{/authRequired}}
              {{#isForm}}
              .contentType("application/x-www-form-urlencoded")
              {{#formFields}}
              .formParam("{{{name}}}", {{{valueExpr}}})
              {{/formFields}}
              {{/isForm}}
              {{^isForm}}
              .contentType("application/json")
  {{^readPath}}            .body({{{bodyExpr}}})
  {{/readPath}}
              {{/isForm}}
  ```

- [ ] **Step 2: Test code generation manually**
  Generate tests on a sample module using `test-generator` and verify that the output Java test class contains the correct `.formParam("name", "val")` syntax.

- [ ] **Step 3: Commit the template update**
  Command: `git commit -am "feat: update test-class.mustache template to support form parameters"`

---

### Task 4: E2E Verification with Samples
**Files:**
- Test Module: `samples/order-service`

- [ ] **Step 1: Run complete exploration and test generation**
  Command: `./e2e/run-e2e.sh`
  Expected: Code generated for form endpoints compiles successfully and REST Assured tests pass.
  
- [ ] **Step 2: Commit all E2E verifications**
