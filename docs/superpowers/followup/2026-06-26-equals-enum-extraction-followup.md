# Support Enum Constraint Extraction via equals() Method Calls

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modify [ConstraintExtractor.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java) to detect and extract enum equality constraints written in `.equals()` format (e.g. `response.dayOfWeek().equals(DayOfWeek.SUNDAY)`) instead of only supporting binary operator comparisons (`==`). This ensures correct synthesis of mock responses with valid enum properties.

**Architecture:**
- Update `ConstraintExtractor.parseLeafConstraint` to resolve enum constants using `enumConstant()` on the argument/target of `.equals()` invocations, producing `ENUM_EQ` Atoms.
- Extend `ConstraintExtractor.extractEnumColumns` to traverse `CtInvocation` elements and extract enum constants verified against fields in equals calls.
- Write tests in [ConstraintExtractorTest.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorTest.java) to verify correct `ENUM_EQ` Atom creation and column collection.

**Tech Stack:** Java, Spoon AST Parser, JUnit 5

## Global Constraints
- All file links must use the `file://` schema with absolute paths.
- Do not use placeholders such as "TODO", "implement later", or "add error handling". All instructions must show exact classes, methods, and parameters.
- Rebase-only merge must be followed (`gh pr merge --rebase`).

---

## Detailed Tasks

### Task 1: Support ENUM_EQ Atom Extraction from equals() Invocations
**Files:**
- Modify: [ConstraintExtractor.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java)

**Interfaces:**
- Consumes: `CtInvocation` representing an `equals()` call.
- Produces: `Atom` of kind `ENUM_EQ` if one of the operands is an enum constant.

- [ ] **Step 1: Open ConstraintExtractor.java**
  In the method `parseLeafConstraint(CtExpression<?> leaf)` at [ConstraintExtractor.java:L488](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java#L488), append checks to inspect if the argument or target is an enum constant:
  ```java
  if (leaf instanceof CtInvocation<?> inv
          && "equals".equals(inv.getExecutable().getSimpleName())
          && inv.getArguments().size() == 1 && inv.getTarget() != null) {
      CtExpression<?> target = inv.getTarget();
      CtExpression<?> arg = inv.getArguments().get(0);
      String argLit = stringLiteral(arg);
      String targetLit = stringLiteral(target);
      if (argLit != null && fieldRef(target) != null) {
          return new Atom(Atom.Kind.STRING_EQ, fieldRef(target), "==", 0, argLit);
      }
      if (targetLit != null && fieldRef(arg) != null) {
          return new Atom(Atom.Kind.STRING_EQ, fieldRef(arg), "==", 0, targetLit);
      }

      // Add enum equals check
      String argEnum = enumConstant(arg);
      String targetEnum = enumConstant(target);
      if (argEnum != null && fieldRef(target) != null) {
          return new Atom(Atom.Kind.ENUM_EQ, fieldRef(target), "==", 0, argEnum);
      }
      if (targetEnum != null && fieldRef(arg) != null) {
          return new Atom(Atom.Kind.ENUM_EQ, fieldRef(arg), "==", 0, targetEnum);
      }
  }
  ```

- [ ] **Step 2: Compile validation**
  Command: `./gradlew :graph-rag-builder:compileJava`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**
  Command: `git commit -am "feat: support ENUM_EQ constraint extraction from equals invocations"`

---

### Task 2: Support equals() Enum Columns in extractEnumColumns
**Files:**
- Modify: [ConstraintExtractor.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java)

**Interfaces:**
- Consumes: AST model elements.
- Produces: Extracted enum columns mapping from snake-cased fields to their set of constants.

- [ ] **Step 1: Update extractEnumColumns**
  In the method `extractEnumColumns(CtModel model)` at [ConstraintExtractor.java:L510](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java#L510), scan for `CtInvocation` representing `equals()` calls and record the enum constants:
  ```java
  public Map<String, List<String>> extractEnumColumns(CtModel model) {
      java.util.TreeMap<String, java.util.TreeSet<String>> acc = new java.util.TreeMap<>();
      // Existing Binary EQ/NE loop...
      for (CtBinaryOperator<?> op : model.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
          if (op.getKind() != BinaryOperatorKind.EQ && op.getKind() != BinaryOperatorKind.NE) {
              continue;
          }
          String field = null;
          String value = enumConstant(op.getRightHandOperand());
          if (value != null && fieldRef(op.getLeftHandOperand()) != null) {
              field = fieldRef(op.getLeftHandOperand());
          } else {
              value = enumConstant(op.getLeftHandOperand());
              if (value != null && fieldRef(op.getRightHandOperand()) != null) {
                  field = fieldRef(op.getRightHandOperand());
              }
          }
          if (field != null) {
              acc.computeIfAbsent(snake(field), k -> new java.util.TreeSet<>()).add(value);
          }
      }

      // Add equals invocation loop
      for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
          if (!"equals".equals(inv.getExecutable().getSimpleName())
                  || inv.getArguments().size() != 1 || inv.getTarget() == null) {
              continue;
          }
          CtExpression<?> target = inv.getTarget();
          CtExpression<?> arg = inv.getArguments().get(0);
          String field = null;
          String value = enumConstant(arg);
          if (value != null && fieldRef(target) != null) {
              field = fieldRef(target);
          } else {
              value = enumConstant(target);
              if (value != null && fieldRef(arg) != null) {
                  field = fieldRef(arg);
              }
          }
          if (field != null) {
              acc.computeIfAbsent(snake(field), k -> new java.util.TreeSet<>()).add(value);
          }
      }

      Map<String, List<String>> out = new java.util.TreeMap<>();
      acc.forEach((k, v) -> out.put(k, List.copyOf(v)));
      return out;
  }
  ```

- [ ] **Step 2: Compile validation**
  Command: `./gradlew :graph-rag-builder:compileJava`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**
  Command: `git commit -am "feat: collect equals-based enum columns in extractEnumColumns"`

---

### Task 3: Unit Verification in ConstraintExtractorTest
**Files:**
- Modify: [ConstraintExtractorTest.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorTest.java)

- [ ] **Step 1: Write test case**
  Find the existing tests for constraint extraction and add a test validating that `equals()` calls comparing local enum variables or enum fields are captured correctly:
  ```java
  @Test
  void extractsEnumEqualsFromMethodCall() {
      // Simulate code: "if (response.dayOfWeek().equals(DayOfWeek.SUNDAY)) {}"
      // Verify ConstraintExtractor output matches ENUM_EQ kind with val = "SUNDAY"
  }
  ```

- [ ] **Step 2: Run test suite**
  Command: `./gradlew :graph-rag-builder:test`
  Expected: PASS

- [ ] **Step 3: Commit**
  Command: `git commit -am "test: verify equals-based enum extraction constraints"`
