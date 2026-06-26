# Parallel Fan-out Builder P1-6 Remaining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the remaining tasks for P1-6 in parallel fan-out builder, including renaming all internal jacoco-related fields to coverage-port, maintaining CLI backward compatibility, verifying Attach mode E2E scripts, and ensuring clean CI environments resolved via Ivy repositories.

**Architecture:**
- Rename internal ports mapping from `jacoco*` to `coverage*` in [OverrideComposeGenerator.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/env/OverrideComposeGenerator.java), [AttachedComposeEnvironment.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/env/AttachedComposeEnvironment.java), and the `AttachConfig` record within [BuilderCli.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java).
- Maintain backward compatibility in [BuilderCli.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java) using `--coverage-port` as default, fallback to `--jacoco-port` with a deprecation warning log if specified.
- Ensure dependency resolution is cleanly configured through Ivy for the pjacoco artifact without requiring `mavenLocal` in clean CI environments (this has been configured in `settings.gradle.kts` and needs to be verified).

**Tech Stack:** Java, Spring Boot, Docker Compose, Gradle, Bash

## Global Constraints
- All file links must use the `file://` schema with absolute paths.
- Do not use placeholders such as "TODO", "implement later", or "add error handling". All instructions must show exact classes, methods, and parameters.
- Rebase-only merge must be followed (`gh pr merge --rebase`).

---

## Detailed Tasks

### Task 1: Rename Internal Port Configurations to coveragePort/coverageContainerPort
**Files:**
- Modify: [AttachedComposeEnvironment.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/env/AttachedComposeEnvironment.java)
- Modify: [OverrideComposeGenerator.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/env/OverrideComposeGenerator.java)
- Modify: [BuilderCli.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java)

**Interfaces:**
- Consumes: Existing config attributes like `jacocoHostPort` or `jacocoContainerPort`.
- Produces: Normalized `coverageHostPort` and `coverageContainerPort` attributes across Environment and Compose Generator classes.

- [ ] **Step 1: Check existing variables and prepare test cases**
  Verify the current variables used in `AttachedComposeEnvironmentTest` and ensure they align with the expected changes.
  
- [ ] **Step 2: Update OverrideComposeGenerator.java**
  Ensure all references in `OverrideComposeGenerator.Spec` and `generate` method are renamed from `jacoco*` to `coverage*`:
  ```java
  public record Spec(
      // ...
      int coverageContainerPort, int coverageHostPort,
      // ...
  )
  ```
  Ensure the generated YAML uses `coverageHostPort` / `coverageContainerPort` inside `ports` mapping.

- [ ] **Step 3: Update AttachedComposeEnvironment.java**
  Ensure the internal `Config` record and methods use:
  ```java
  public record Config(
      // ...
      String coverageHost, int coveragePort,
      // ...
  )
  ```

- [ ] **Step 4: Update BuilderCli.java AttachConfig**
  Rename the fields inside the `AttachConfig` record:
  ```java
  public record AttachConfig(
      Path userCompose, String appService,
      int appContainerPort, int appHostPort, int coverageHostPort,
      // ...
  )
  ```

- [ ] **Step 5: Run tests and commit**
  Command: `./gradlew :graph-rag-builder:test -PincludeTags=integration`
  Expected: All tests pass.
  Command: `git add . && git commit -m "refactor: rename internal jacoco ports to coverage ports"`

---

### Task 2: Implement CLI Option Alias and Warning for `--jacoco-port`
**Files:**
- Modify: [BuilderCli.java](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java)

**Interfaces:**
- Consumes: Options map containing `--coverage-port` or `--jacoco-port`.
- Produces: Resolved coverage port as `int` and outputs a deprecation warning if `--jacoco-port` is used.

- [ ] **Step 1: Locate CLI option parser in BuilderCli.java**
  Find the code resolving `--coverage-port` inside `BuilderCli.java`.

- [ ] **Step 2: Add fallback logic**
  Implement the option resolver method:
  ```java
  private static int resolveCoveragePort(Map<String, String> options) {
      if (options.containsKey("--coverage-port")) {
          return Integer.parseInt(options.get("--coverage-port"));
      }
      if (options.containsKey("--jacoco-port")) {
          log.warn("WARNING: --jacoco-port is deprecated, please use --coverage-port instead.");
          return Integer.parseInt(options.get("--jacoco-port"));
      }
      throw new IllegalArgumentException("Missing required option: --coverage-port");
  }
  ```

- [ ] **Step 3: Test CLI option fallback**
  Add a test inside `AttachCliConfigTest.java` to verify that passing `--jacoco-port` correctly parses into the configuration with a logged warning.
  Command: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.cli.AttachCliConfigTest"`
  Expected: PASS

- [ ] **Step 4: Commit changes**
  Command: `git commit -am "feat: support --jacoco-port deprecated option fallback to --coverage-port"`

---

### Task 3: Verify Ivy dependency resolution in clean CI environment
**Files:**
- Verify: [settings.gradle.kts](file:///root/graph-rag-test-generator/settings.gradle.kts)
- Verify: [build.gradle.kts](file:///root/graph-rag-test-generator/graph-rag-builder/build.gradle.kts)

- [ ] **Step 1: Clear local gradle cache**
  Remove local `.m2` dependency to ensure it resolves from the ivy release repo:
  Command: `rm -rf ~/.m2/repository/io/pjacoco/`
  
- [ ] **Step 2: Run build in clean mode**
  Command: `./gradlew :graph-rag-builder:build --no-build-cache`
  Expected: Build completes successfully, verifying the Ivy repository downloads `pjacoco-agent-1.3.0.jar` correctly.

- [ ] **Step 3: Commit verification details (if any settings require adjustment)**

---

### Task 4: Run E2E Verification for Attach Mode
**Files:**
- Execute: `e2e/run-attach-otel-e2e.sh`
- Execute: `e2e/run-attach-ext-http-e2e.sh`

- [ ] **Step 1: Run attach OTEL E2E script**
  Command: `./e2e/run-attach-otel-e2e.sh`
  Expected: Explores order-service endpoints and asserts that trace records and pjacoco coverage are successfully generated.
  
- [ ] **Step 2: Run attach External HTTP E2E script**
  Command: `./e2e/run-attach-ext-http-e2e.sh`
  Expected: Captures external HTTP calls and successfully generates the pruned graph.

- [ ] **Step 3: Document any findings in the PR**
