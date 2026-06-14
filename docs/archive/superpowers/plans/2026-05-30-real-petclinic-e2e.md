# Real-Petclinic E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the merged 6-stage orchestrator actually compose against a real `spring-petclinic` SUT end-to-end, in a 5-iteration coverage-feedback loop, and ship a reproducible runbook with one live run's numbers.

**Architecture:** Two surgical orchestrator wiring fixes (per-iteration `output.archive-dir` rewrite; Stage-5 args passthrough) + one Bash wrapper script that drives `mvn test jacoco:report` against the petclinic clone with cumulative `iter-*/stage4-tests` semantics + a YAML template (config.yml minus `scout.steps`). The orchestrator binary, `OrchestratorConfig`, and Stage-1/2/6 surface stay unchanged.

**Tech Stack:** Java 17 + Gradle 8.x for orchestrator; Bash for Stage-5 wrapper; Maven + JaCoCo 0.8.14 (already configured in petclinic upstream pom); Docker Compose for Postgres; the existing `scout-launcher`, `test-generator`, `scout-step-translator`, and `graph-rag-builder` modules in this repo are reused as-is.

**Build flag:** Every gradle invocation in this plan must include `-Pagent.enabled=true` and `JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home` (pre-existing repo constraint; see handoff §7).

---

## File map

**New files:**
- `samples/scout/petclinic/template.yml` — config.yml minus `scout.steps`, with `teardown-on-exit: false`
- `scripts/petclinic-stage5.sh` — Bash wrapper, executable bit set
- `orchestrator/src/test/java/io/graphrag/orchestrator/ExternalStageRunnerShellTest.java` — unit test for arg passthrough
- `orchestrator/src/test/java/io/graphrag/orchestrator/IterationRunnerArchiveDirTest.java` — unit test for archive-dir rewrite
- `orchestrator/src/test/java/io/graphrag/orchestrator/PetclinicStage5ScriptTest.java` — wrapper-script unit test driven from a Java test (uses `ProcessBuilder` + a stub `mvn` shell script on `PATH`)
- `docs/orchestrator-e2e-petclinic.md` — runbook, written AFTER live run

**Modified files:**
- `orchestrator/src/main/java/io/graphrag/orchestrator/ExternalStageRunner.java` — `Shell.runTestsAndJacoco` appends positional args
- `orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java` — post-edit `stage2-config.yml`'s `output.archive-dir`

**Out of scope (do not touch):**
- `OrchestratorConfig`, `Orchestrator` CLI, `IterationLayout`, `ReportGenerator`
- `ScoutStepTranslator` (its scoping in spec §2.2 is intentional)
- Existing tests in `OrchestratorE2eTest` (must keep passing as-is — verified at Task 7)

---

## Task 1: Failing test for `Shell.runTestsAndJacoco` arg passthrough

**Files:**
- Create: `orchestrator/src/test/java/io/graphrag/orchestrator/ExternalStageRunnerShellTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalStageRunnerShellTest {

    @Test
    void runTestsAndJacoco_appendsTestsDirAndJacocoOutAsPositionalArgs(@TempDir Path tmp)
            throws IOException, InterruptedException {
        // Stub user-test command: a shell script that records argv to a sentinel
        // and touches the requested jacoco.xml so Shell's post-condition is satisfied.
        Path script = tmp.resolve("echo-args.sh");
        Path sentinel = tmp.resolve("argv.txt");
        Files.writeString(script, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\\n' "$@" > "%s"
                # Stage-5 contract: second positional arg is the jacoco.xml destination.
                : > "$2"
                """.formatted(sentinel));
        Files.setPosixFilePermissions(script,
                PosixFilePermissions.fromString("rwxr-xr-x"));

        Path testsDir  = tmp.resolve("iter-1/stage4-tests");
        Path jacocoOut = tmp.resolve("iter-1/stage5-jacoco.xml");
        Files.createDirectories(testsDir);

        ExternalStageRunner.Shell shell = new ExternalStageRunner.Shell(
                tmp.resolve("unused-scout"),
                tmp.resolve("unused-tg"),
                List.of(script.toString()));
        shell.runTestsAndJacoco(testsDir, jacocoOut);

        assertThat(Files.readAllLines(sentinel))
                .containsExactly(testsDir.toString(), jacocoOut.toString());
        assertThat(Files.exists(jacocoOut)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests io.graphrag.orchestrator.ExternalStageRunnerShellTest
```

Expected: FAIL — assertion shows sentinel argv is empty (Shell ignores its args today). The empty `userTestCommand` list passes no args at all, so `argv.txt` is empty.

- [ ] **Step 3: Commit the failing test**

```bash
git add orchestrator/src/test/java/io/graphrag/orchestrator/ExternalStageRunnerShellTest.java
git commit -m "test(orchestrator): pin Shell.runTestsAndJacoco arg-passthrough contract (RED)"
```

---

## Task 2: Implement Shell arg passthrough

**Files:**
- Modify: `orchestrator/src/main/java/io/graphrag/orchestrator/ExternalStageRunner.java` (the `Shell.runTestsAndJacoco` method)

- [ ] **Step 1: Edit `Shell.runTestsAndJacoco`**

Replace the existing method body with:

```java
@Override
public void runTestsAndJacoco(Path generatedTestsDir, Path jacocoOut)
        throws IOException, InterruptedException {
    // The wrapper script needs to know which iter-N to read from and where to write
    // jacoco.xml; both are per-iteration so they can't live in userTestCommand itself.
    java.util.List<String> cmd = new java.util.ArrayList<>(userTestCommand);
    cmd.add(generatedTestsDir.toString());
    cmd.add(jacocoOut.toString());
    spawn(cmd);
    if (!Files.exists(jacocoOut)) {
        throw new IOException("expected JaCoCo XML at " + jacocoOut
                + " but the user test command did not produce it");
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests io.graphrag.orchestrator.ExternalStageRunnerShellTest
```

Expected: PASS.

- [ ] **Step 3: Run full orchestrator suite to confirm no regressions**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test
```

Expected: PASS (in particular `OrchestratorE2eTest` should still pass — its `FakeExternal` doesn't rely on argv contents).

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/io/graphrag/orchestrator/ExternalStageRunner.java
git commit -m "feat(orchestrator): pass tests-dir + jacoco-out as positional args to user command"
```

---

## Task 3: Failing test for IterationRunner archive-dir rewrite

**Files:**
- Create: `orchestrator/src/test/java/io/graphrag/orchestrator/IterationRunnerArchiveDirTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.graphrag.feedback.MissingBranch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wiring fix from spec §2.2: every iteration's stage2-config.yml must end up
 * with output.archive-dir pointing at THIS iter's stage3-archive, regardless of what
 * the template said. Without this, the real Shell runner can't compose with the iter-
 * scoped reader contract.
 */
class IterationRunnerArchiveDirTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void afterStage2_outputArchiveDir_pointsAtIterStage3Archive(@TempDir Path tmp)
            throws Exception {
        // Build a minimal SUT source tree with one endpoint so Stage 1 produces non-empty
        // paths/endpoints; this is the same fixture shape OrchestratorE2eTest uses.
        Path sutSrc = tmp.resolve("sut/src/main/java/demo");
        Files.createDirectories(sutSrc);
        Files.writeString(sutSrc.resolve("Demo.java"), """
                package demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                class Demo {
                    @GetMapping("/ping")
                    public String ping() { return "pong"; }
                }
                """);

        // Template ships an explicit (wrong-for-this-iter) archive-dir.
        Path template = tmp.resolve("template.yml");
        Files.writeString(template, """
                sut: { jar: /dev/null }
                output:
                  archive-dir: /tmp/should-be-overwritten
                  project: demo
                """);

        Path outDir = tmp.resolve("out");

        OrchestratorConfig cfg = new OrchestratorConfig(
                tmp.resolve("sut/src/main/java"),
                "demo",
                "demo.tests",
                template,
                "http://localhost:0",
                outDir,
                0.70,
                1);

        RecordingExternal external = new RecordingExternal();
        IterationRunner runner = new IterationRunner(cfg, external,
                new PrintStream(new ByteArrayOutputStream()));

        runner.runOne(1, List.of(), List.of(), Set.of());

        Path stage2Config = outDir.resolve("iter-1/stage2-config.yml");
        assertThat(stage2Config).exists();
        ObjectNode root = (ObjectNode) YAML.readTree(stage2Config.toFile());
        String archiveDir = root.path("output").path("archive-dir").asText();
        assertThat(archiveDir)
                .isEqualTo(outDir.resolve("iter-1/stage3-archive").toString());
    }

    /** Records nothing; just satisfies the Stage 3/4/5 contracts so runOne completes. */
    private static final class RecordingExternal implements ExternalStageRunner {
        @Override public void runScout(Path configYaml, Path archiveDir) throws IOException {
            Files.createDirectories(archiveDir);
        }
        @Override public void runTestGenerator(Path archiveDir, List<String> endpointIds,
                                               String testPackage, Path outDir) throws IOException {
            Files.createDirectories(outDir);
        }
        @Override public void runTestsAndJacoco(Path generatedTestsDir, Path jacocoOut)
                throws IOException {
            Files.createDirectories(jacocoOut.getParent());
            // Smallest JaCoCo XML that JaCoCoXmlParser will accept (no branches → ratio 0).
            Files.writeString(jacocoOut, """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                    <report name="demo"></report>
                    """);
        }
    }
}
```

Note: if the minimal JaCoCo XML above is rejected by `JaCoCoXmlParser`, the test will fail at Stage 6, not Stage 2. The archive-dir assertion still fires before the exception thanks to the file being written during Stage 2. If runOne throws before the assertion can read the file, restructure to call only `IterationRunner` internals exposed for tests, or split into a Stage-2-only helper — but the simplest path is keeping this end-to-end.

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests io.graphrag.orchestrator.IterationRunnerArchiveDirTest
```

Expected: FAIL — `archiveDir` reads `/tmp/should-be-overwritten` (the template's literal value), not the iter's `stage3-archive` path.

- [ ] **Step 3: Commit the failing test**

```bash
git add orchestrator/src/test/java/io/graphrag/orchestrator/IterationRunnerArchiveDirTest.java
git commit -m "test(orchestrator): pin per-iter archive-dir rewrite contract (RED)"
```

---

## Task 4: Implement archive-dir rewrite in IterationRunner

**Files:**
- Modify: `orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java`

- [ ] **Step 1: Add imports + patch logic**

At the top of `IterationRunner.java`, add (after existing imports):

```java
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
```

Just after the existing `ScoutStepTranslator.translate(...)` call inside `runOne`, insert:

```java
// Stage 2 step (b): repoint scout-launcher's archive output at this iter's slot.
// ExternalStageRunner.Shell.runScout ignores its archiveDir arg and just spawns
// scout-launcher on the YAML, so the YAML itself has to carry the per-iter path.
ObjectMapper iterYaml = new ObjectMapper(new YAMLFactory()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
ObjectNode iterRoot = (ObjectNode) iterYaml.readTree(layout.stage2Config().toFile());
iterRoot.with("output").put("archive-dir", layout.stage3Archive().toString());
Files.write(layout.stage2Config(),
        iterYaml.writerWithDefaultPrettyPrinter().writeValueAsString(iterRoot).getBytes());
```

- [ ] **Step 2: Run the new test to verify it passes**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests io.graphrag.orchestrator.IterationRunnerArchiveDirTest
```

Expected: PASS.

- [ ] **Step 3: Run full orchestrator suite to confirm `OrchestratorE2eTest` still passes**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test
```

Expected: PASS for all orchestrator tests. The pre-existing `OrchestratorE2eTest` uses `FakeExternal` and does not depend on the YAML content of `stage2-config.yml`, so it should be unaffected.

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/io/graphrag/orchestrator/IterationRunner.java
git commit -m "feat(orchestrator): rewrite output.archive-dir per iteration after Stage 2 translate"
```

---

## Task 5: Create `samples/scout/petclinic/template.yml`

**Files:**
- Create: `samples/scout/petclinic/template.yml`

- [ ] **Step 1: Write the template**

Exact content:

```yaml
# scout-launcher template — spring-petclinic + Postgres.
# Used by the orchestrator's Stage 2: ScoutStepTranslator overwrites the entire
# `scout:` block per-iteration with paths from Stage 1, and IterationRunner
# overwrites `output.archive-dir` with the iter-specific stage3-archive path.

sut:
  jar: ${HOME}/github_spring-petclinic/spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar
  args:
    - --server.port=8084
    - --spring.profiles.active=postgres
    - --spring.datasource.url=jdbc:postgresql://localhost:55432/petclinic
    - --spring.datasource.username=appuser
    - --spring.datasource.password=apppass
    - --spring.datasource.driver-class-name=org.postgresql.Driver

  jvm-args:
    - -Xmx512m

  agents:
    - ${HOME}/.m2/repository/io/jdbcintercept/agent-core/1.0.0-SNAPSHOT/agent-core-1.0.0-SNAPSHOT.jar

  boot-classpath:
    - ${HOME}/.m2/repository/io/graphrag/graph-rag-builder/0.1.0-SNAPSHOT/graph-rag-builder-0.1.0-SNAPSHOT.jar
    - ${HOME}/.m2/repository/io/graphrag/shared-model/0.1.0-SNAPSHOT/shared-model-0.1.0-SNAPSHOT.jar
    - ${HOME}/.m2/repository/io/jdbcintercept/agent-api/1.0.0-SNAPSHOT/agent-api-1.0.0-SNAPSHOT.jar

  health-check:
    url: http://localhost:8084/actuator/health
    timeout-seconds: 60
    interval-millis: 1000

  otel:
    enabled: true

dependencies:
  docker-compose:
    file: ./samples/scout/petclinic/docker-compose.yml
    wait-for-healthy: true
    # Keep containers up across iterations — the orchestrator's 5-iter loop would
    # otherwise pay a ~30 s cold-start tax per iter. The runbook tells the operator
    # to tear them down with `docker compose down` at the end.
    teardown-on-exit: false
    health-timeout-seconds: 120

output:
  # Placeholder — IterationRunner overwrites this with iter-N/stage3-archive per iter.
  archive-dir: /tmp/graph-rag-scout/petclinic-archive
  clear-before-run: true
  preserve-files:
    - paths.json
    - endpoints.json
  strict-mode: true
  project: petclinic
```

- [ ] **Step 2: Verify diff vs config.yml is minimal**

Run:
```bash
diff samples/scout/petclinic/config.yml samples/scout/petclinic/template.yml | head -40
```

Expected: only differences should be (a) the entire `scout:` block missing from `template.yml`, (b) `teardown-on-exit: false` vs `true`, (c) the comment differences.

- [ ] **Step 3: Commit**

```bash
git add samples/scout/petclinic/template.yml
git commit -m "feat(samples): petclinic template.yml for orchestrator-driven scout runs"
```

---

## Task 6: Failing test for the Stage-5 wrapper script (happy path)

**Files:**
- Create: `orchestrator/src/test/java/io/graphrag/orchestrator/PetclinicStage5ScriptTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives scripts/petclinic-stage5.sh from a Java test using a stub `mvn` on PATH and
 * a fake petclinic clone with a stock pom.xml. This is cheaper than wiring bats into
 * the build and keeps the test inside the existing :orchestrator:test phase.
 */
class PetclinicStage5ScriptTest {

    @Test
    void copiesAllPriorItersAndWritesJacocoXml(@TempDir Path tmp) throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path script = projectRoot.resolve("scripts/petclinic-stage5.sh");
        assertThat(script).as("wrapper script must exist").exists();

        // Stub `mvn` — produces the jacoco.xml at the path petclinic's pom would.
        Path stubBin = tmp.resolve("stub-bin");
        Files.createDirectories(stubBin);
        Path mvnStub = stubBin.resolve("mvn");
        Files.writeString(mvnStub, """
                #!/usr/bin/env bash
                set -euo pipefail
                # The wrapper cd's into $PETCLINIC_DIR before invoking us. Honor that.
                mkdir -p target/site/jacoco
                cat > target/site/jacoco/jacoco.xml <<'XML'
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="petclinic"></report>
                XML
                """);
        Files.setPosixFilePermissions(mvnStub,
                PosixFilePermissions.fromString("rwxr-xr-x"));

        // Fake petclinic clone — just needs a pom.xml so the wrapper accepts it.
        Path petclinic = tmp.resolve("fake-petclinic");
        Files.createDirectories(petclinic.resolve("src/test/java"));
        Files.writeString(petclinic.resolve("pom.xml"), "<project/>");

        // Orchestrator outDir with two prior iters' stage4-tests, plus the current one.
        Path outDir = tmp.resolve("out");
        Path iter1Tests = outDir.resolve("iter-1/stage4-tests");
        Path iter2Tests = outDir.resolve("iter-2/stage4-tests");
        Files.createDirectories(iter1Tests);
        Files.createDirectories(iter2Tests);
        Files.writeString(iter1Tests.resolve("A.java"), "class A {}");
        Files.writeString(iter2Tests.resolve("B.java"), "class B {}");

        Path jacocoOut = outDir.resolve("iter-2/stage5-jacoco.xml");
        Files.createDirectories(jacocoOut.getParent());

        Map<String, String> env = new HashMap<>();
        env.put("PETCLINIC_DIR", petclinic.toString());
        env.put("TEST_PACKAGE", "com.example.petclinic.tests");
        env.put("PATH", stubBin + ":" + System.getenv("PATH"));

        ProcessBuilder pb = new ProcessBuilder(
                "bash", script.toString(),
                iter2Tests.toString(), jacocoOut.toString());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        assertThat(rc).as("script stdout was:\n%s", stdout).isZero();

        assertThat(jacocoOut).exists();
        // Cleanup trap should have removed the injected test pkg under petclinic.
        Path injected = petclinic.resolve("src/test/java/com/example/petclinic/tests");
        assertThat(injected).doesNotExist();
    }
}
```

- [ ] **Step 2: Run test to verify it fails (script not yet created)**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests io.graphrag.orchestrator.PetclinicStage5ScriptTest
```

Expected: FAIL with the assertion `wrapper script must exist`.

- [ ] **Step 3: Commit the failing test**

```bash
git add orchestrator/src/test/java/io/graphrag/orchestrator/PetclinicStage5ScriptTest.java
git commit -m "test(orchestrator): pin petclinic-stage5.sh cumulative-copy + cleanup contract (RED)"
```

---

## Task 7: Implement `scripts/petclinic-stage5.sh`

**Files:**
- Create: `scripts/petclinic-stage5.sh` (mode 0755)

- [ ] **Step 1: Verify `scripts/` directory does not yet exist, then create it**

Run:
```bash
ls scripts 2>&1 || mkdir -p scripts && ls scripts
```

Expected: either `ls: scripts: No such file or directory` followed by an empty `scripts` listing, or just an empty `scripts/` listing.

- [ ] **Step 2: Write the script**

Exact content:

```bash
#!/usr/bin/env bash
# Stage-5 wrapper for the orchestrator's petclinic E2E. The orchestrator invokes
# this with two positional args:
#   $1 — generatedTestsDir (this iter's iter-N/stage4-tests)
#   $2 — jacocoOut         (where to write the cumulative jacoco.xml)
#
# Cumulative-coverage strategy: we glob every iter-*/stage4-tests under the
# orchestrator outDir and copy ALL of them into petclinic's test source tree
# before running `mvn test jacoco:report`. This way iter-N's reported coverage
# reflects all generated tests so far, not just this iter's subset (which is
# pruned by Stage 6's excludePaths in later iters).
#
# Env:
#   PETCLINIC_DIR — path to spring-petclinic clone (default: ~/github_spring-petclinic/spring-petclinic)
#   TEST_PACKAGE  — destination package for generated tests (default: com.example.petclinic.tests)
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <stage4-tests-dir> <jacoco-out-path>" >&2
  exit 2
fi

TESTS_DIR="$1"
JACOCO_OUT="$2"
PETCLINIC_DIR="${PETCLINIC_DIR:-$HOME/github_spring-petclinic/spring-petclinic}"
TEST_PACKAGE="${TEST_PACKAGE:-com.example.petclinic.tests}"

if [[ ! -f "$PETCLINIC_DIR/pom.xml" ]]; then
  echo "error: PETCLINIC_DIR=$PETCLINIC_DIR does not contain a pom.xml" >&2
  exit 3
fi
if [[ ! -d "$TESTS_DIR" ]]; then
  echo "error: tests-dir does not exist: $TESTS_DIR" >&2
  exit 4
fi

OUT_DIR="$(cd "$(dirname "$TESTS_DIR")/.." && pwd)"
TEST_PACKAGE_PATH="$(echo "$TEST_PACKAGE" | tr . /)"
INJECTED_ROOT="$PETCLINIC_DIR/src/test/java/$TEST_PACKAGE_PATH"

cleanup() {
  rm -rf "$INJECTED_ROOT"
}
trap cleanup EXIT

mkdir -p "$INJECTED_ROOT"

# Copy ALL prior + current iters' generated tests. Sort lexicographically so
# iter-2's outputs win over iter-1's for any same-named class (later iters
# typically regenerate only fewer endpoints, so collisions are rare; when they
# do happen, latest wins is the desired semantic).
shopt -s nullglob
for dir in $(printf '%s\n' "$OUT_DIR"/iter-*/stage4-tests | sort); do
  [[ -d "$dir" ]] || continue
  cp -R "$dir"/. "$INJECTED_ROOT"/
done
shopt -u nullglob

echo "[stage5] cumulative tests copied to $INJECTED_ROOT"
(
  cd "$PETCLINIC_DIR"
  mvn -q -DskipITs test jacoco:report
)

JACOCO_SRC="$PETCLINIC_DIR/target/site/jacoco/jacoco.xml"
if [[ ! -f "$JACOCO_SRC" ]]; then
  echo "error: jacoco.xml not produced at $JACOCO_SRC" >&2
  exit 5
fi

mkdir -p "$(dirname "$JACOCO_OUT")"
cp "$JACOCO_SRC" "$JACOCO_OUT"
echo "[stage5] wrote $JACOCO_OUT"
```

- [ ] **Step 3: Make it executable**

Run:
```bash
chmod +x scripts/petclinic-stage5.sh
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests io.graphrag.orchestrator.PetclinicStage5ScriptTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/petclinic-stage5.sh
git update-index --chmod=+x scripts/petclinic-stage5.sh
git commit -m "feat(scripts): Stage 5 wrapper — cumulative iter-* copy + mvn jacoco:report"
```

---

## Task 8: Add wrapper script error-path tests

**Files:**
- Modify: `orchestrator/src/test/java/io/graphrag/orchestrator/PetclinicStage5ScriptTest.java` (append three test methods)

- [ ] **Step 1: Append three failure-mode tests**

Add inside the existing class, after the happy-path test:

```java
@Test
void failsWhenPetclinicDirHasNoPom(@TempDir Path tmp) throws Exception {
    Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
    Path script = projectRoot.resolve("scripts/petclinic-stage5.sh");

    Path emptyDir = tmp.resolve("no-pom-here");
    Files.createDirectories(emptyDir);

    Path testsDir = tmp.resolve("out/iter-1/stage4-tests");
    Files.createDirectories(testsDir);

    ProcessBuilder pb = new ProcessBuilder("bash", script.toString(),
            testsDir.toString(), tmp.resolve("jacoco.xml").toString());
    pb.environment().put("PETCLINIC_DIR", emptyDir.toString());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String stdout = new String(p.getInputStream().readAllBytes());
    int rc = p.waitFor();
    assertThat(rc).as("expected nonzero exit; stdout:\n%s", stdout).isEqualTo(3);
}

@Test
void failsWhenTestsDirMissing(@TempDir Path tmp) throws Exception {
    Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
    Path script = projectRoot.resolve("scripts/petclinic-stage5.sh");

    Path petclinic = tmp.resolve("fake-petclinic");
    Files.createDirectories(petclinic.resolve("src/test/java"));
    Files.writeString(petclinic.resolve("pom.xml"), "<project/>");

    ProcessBuilder pb = new ProcessBuilder("bash", script.toString(),
            tmp.resolve("does-not-exist").toString(),
            tmp.resolve("jacoco.xml").toString());
    pb.environment().put("PETCLINIC_DIR", petclinic.toString());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    int rc = p.waitFor();
    assertThat(rc).isEqualTo(4);
}

@Test
void cleanupTrapFiresEvenWhenMvnFails(@TempDir Path tmp) throws Exception {
    Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
    Path script = projectRoot.resolve("scripts/petclinic-stage5.sh");

    // mvn stub that exits nonzero — wrapper must still clean injected tests.
    Path stubBin = tmp.resolve("stub-bin");
    Files.createDirectories(stubBin);
    Path mvnStub = stubBin.resolve("mvn");
    Files.writeString(mvnStub, """
            #!/usr/bin/env bash
            exit 1
            """);
    Files.setPosixFilePermissions(mvnStub,
            PosixFilePermissions.fromString("rwxr-xr-x"));

    Path petclinic = tmp.resolve("fake-petclinic");
    Files.createDirectories(petclinic.resolve("src/test/java"));
    Files.writeString(petclinic.resolve("pom.xml"), "<project/>");

    Path iter1Tests = tmp.resolve("out/iter-1/stage4-tests");
    Files.createDirectories(iter1Tests);
    Files.writeString(iter1Tests.resolve("A.java"), "class A {}");

    ProcessBuilder pb = new ProcessBuilder("bash", script.toString(),
            iter1Tests.toString(),
            tmp.resolve("out/iter-1/stage5-jacoco.xml").toString());
    pb.environment().put("PETCLINIC_DIR", petclinic.toString());
    pb.environment().put("TEST_PACKAGE", "com.example.petclinic.tests");
    pb.environment().put("PATH",
            stubBin + ":" + System.getenv("PATH"));
    pb.redirectErrorStream(true);
    Process p = pb.start();
    int rc = p.waitFor();
    assertThat(rc).as("mvn stub exits 1; wrapper should propagate").isNotZero();

    Path injected = petclinic.resolve("src/test/java/com/example/petclinic/tests");
    assertThat(injected).as("cleanup trap should fire even on failure")
            .doesNotExist();
}
```

- [ ] **Step 2: Run the new tests**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:test \
  --tests io.graphrag.orchestrator.PetclinicStage5ScriptTest
```

Expected: all 4 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add orchestrator/src/test/java/io/graphrag/orchestrator/PetclinicStage5ScriptTest.java
git commit -m "test(orchestrator): cover petclinic-stage5.sh error paths + cleanup trap"
```

---

## Task 9: Verify `:orchestrator:installDist` builds a working binary

**Files:**
- None (verification only). If it fails, escalate to fix `orchestrator/build.gradle.kts` in a separate commit.

- [ ] **Step 1: Build installDist**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true :orchestrator:installDist
```

Expected: BUILD SUCCESSFUL. `orchestrator/build/install/orchestrator/bin/orchestrator` should exist.

- [ ] **Step 2: Sanity-check `--help`-equivalent**

Run:
```bash
./orchestrator/build/install/orchestrator/bin/orchestrator 2>&1 || true
```

Expected: prints `usage: orchestrator …` (the multi-line usage from `Orchestrator.usage()`) and exits with code 2. (The CLI doesn't have an explicit `--help`; running with no flags prints usage.)

- [ ] **Step 3: Run full check to confirm nothing is broken**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true check
```

Expected: BUILD SUCCESSFUL across all modules. If a module other than orchestrator fails, it's likely a pre-existing issue; flag it but do not fix in this plan.

- [ ] **Step 4: No commit** (verification only)

---

## Task 10: Live prereq check + 1-iter smoke run

This task is partly manual (depends on external services). The agent runs the commands, reports any failure, and only proceeds to Task 11 when the smoke output is green.

**Files:**
- None new. Output lands under `/tmp/graph-rag-petclinic-e2e-smoke/iter-1/...`.

- [ ] **Step 1: Verify prereq clones + Maven Local publishes**

Run:
```bash
test -d ~/github_spring-petclinic/spring-petclinic && echo "petclinic OK" || echo "MISSING"
test -d ~/github_jdbc-intercept-agent/jdbc-intercept-agent && echo "agent OK" || echo "MISSING"
test -f ~/github_spring-petclinic/spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar && echo "jar OK" || echo "MISSING"
test -f ~/.m2/repository/io/jdbcintercept/agent-core/1.0.0-SNAPSHOT/agent-core-1.0.0-SNAPSHOT.jar && echo "agent published OK" || echo "MISSING"
test -f ~/.m2/repository/io/graphrag/graph-rag-builder/0.1.0-SNAPSHOT/graph-rag-builder-0.1.0-SNAPSHOT.jar && echo "builder published OK" || echo "MISSING"
```

Expected: all 5 `OK`. If any `MISSING`, run the corresponding step from handoff §3.3 before continuing. The user has already verified petclinic + jar exist at commit `e4bebf2`.

- [ ] **Step 2: Bring Postgres up**

Run:
```bash
docker compose -f samples/scout/petclinic/docker-compose.yml up -d
```

Expected: containers `graphrag-scout-pg`, `graphrag-scout-redis`, `graphrag-scout-kafka` healthy within ~30 s. Verify with:
```bash
docker compose -f samples/scout/petclinic/docker-compose.yml ps
```

- [ ] **Step 3: Build the two installDist binaries needed by the orchestrator**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true \
  :scout-launcher:installDist :test-generator:installDist
```

Expected: BUILD SUCCESSFUL. Binaries at `scout-launcher/build/install/scout-launcher/bin/scout-launcher` and `test-generator/build/install/test-generator/bin/test-generator`.

- [ ] **Step 4: Run 1-iter smoke**

Run:
```bash
SMOKE_OUT=/tmp/graph-rag-petclinic-e2e-smoke
rm -rf "$SMOKE_OUT"
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./orchestrator/build/install/orchestrator/bin/orchestrator \
    --sut-source            "$HOME/github_spring-petclinic/spring-petclinic/src/main/java" \
    --project               petclinic \
    --test-package          com.example.petclinic.tests \
    --scout-config-template samples/scout/petclinic/template.yml \
    --scout-base-url        http://localhost:8084 \
    --out                   "$SMOKE_OUT" \
    --coverage-target       0.70 \
    --max-iterations        1 \
    --scout-launcher-bin    ./scout-launcher/build/install/scout-launcher/bin/scout-launcher \
    --test-generator-bin    ./test-generator/build/install/test-generator/bin/test-generator \
    --user-test-command     ./scripts/petclinic-stage5.sh
```

Expected:
- Exit code 0.
- `$SMOKE_OUT/iter-1/stage1-discovery/{paths,endpoints}.json` non-empty.
- `$SMOKE_OUT/iter-1/stage2-config.yml` exists; `output.archive-dir` = `$SMOKE_OUT/iter-1/stage3-archive`.
- `$SMOKE_OUT/iter-1/stage3-archive/<path-id>/` populated for each endpoint scout-launcher successfully hit (some may be quarantined — that's OK).
- `$SMOKE_OUT/iter-1/stage4-tests/com/example/petclinic/tests/*.java` non-empty for at least one endpoint.
- `$SMOKE_OUT/iter-1/stage5-jacoco.xml` exists and contains `<report name="petclinic">`.
- `$SMOKE_OUT/iter-1/stage6-feedback/termination-decision.json` parses.
- `$SMOKE_OUT/final-report.md` produced.

- [ ] **Step 5: Quick inspection commands (run + record output for the runbook)**

Run:
```bash
echo "=== stage2 archive-dir line ==="
grep -A1 "^output" "$SMOKE_OUT/iter-1/stage2-config.yml" | head -5
echo "=== archive subdirs ==="
ls "$SMOKE_OUT/iter-1/stage3-archive/" | head
echo "=== synthesized tests ==="
find "$SMOKE_OUT/iter-1/stage4-tests" -name '*.java' | head
echo "=== termination decision ==="
cat "$SMOKE_OUT/iter-1/stage6-feedback/termination-decision.json"
```

Save the outputs — they're inputs to Task 12 (runbook).

- [ ] **Step 6: No commit** (just verification; numbers go in the runbook)

If the smoke run fails:
- If Stage 1 produces zero endpoints, inspect the static analyzer with `:graph-rag-builder:staticanalysis-cli`.
- If Stage 3 fails (scout-launcher), check Postgres is up and the SUT jar path is right.
- If Stage 4 fails, check `test-generator` reads from the per-path archive subdir.
- If Stage 5 fails on mvn, run the wrapper script manually with the same args and check `cd $PETCLINIC_DIR && mvn -q -DskipITs test jacoco:report` works standalone.
- Do NOT proceed to Task 11 until the smoke run is green.

---

## Task 11: 5-iter acceptance run

**Files:**
- None new. Output lands under `/tmp/graph-rag-petclinic-e2e/iter-{1..5}/...`.

- [ ] **Step 1: Run the full 5-iter loop**

Run:
```bash
ACCEPT_OUT=/tmp/graph-rag-petclinic-e2e
rm -rf "$ACCEPT_OUT"
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./orchestrator/build/install/orchestrator/bin/orchestrator \
    --sut-source            "$HOME/github_spring-petclinic/spring-petclinic/src/main/java" \
    --project               petclinic \
    --test-package          com.example.petclinic.tests \
    --scout-config-template samples/scout/petclinic/template.yml \
    --scout-base-url        http://localhost:8084 \
    --out                   "$ACCEPT_OUT" \
    --coverage-target       0.70 \
    --max-iterations        5 \
    --scout-launcher-bin    ./scout-launcher/build/install/scout-launcher/bin/scout-launcher \
    --test-generator-bin    ./test-generator/build/install/test-generator/bin/test-generator \
    --user-test-command     ./scripts/petclinic-stage5.sh \
    2>&1 | tee "$ACCEPT_OUT.log"
```

Expected: exit 0. Wall-clock ~8-15 min (5× mvn test).

- [ ] **Step 2: Collect per-iter numbers**

Run:
```bash
for i in 1 2 3 4 5; do
  d="$ACCEPT_OUT/iter-$i/stage6-feedback"
  [[ -d "$d" ]] || break
  echo "=== iter $i ==="
  jq -r '"branch=\(.branchCoverage) newly_covered=\(.newlyCovered|length) still_missing=\(.stillMissing|length)"' "$d/coverage-delta.json"
  jq -r '"terminate=\(.shouldTerminate) reason=\(.reason) target_reached=\(.targetReached)"' "$d/termination-decision.json"
done
```

Expected: monotonically non-decreasing `branchCoverage` (the wrapper's cumulative-copy semantic should guarantee this). Loop either terminates at target (`targetReached=true`) or via no-progress detection or by exhausting iterations.

- [ ] **Step 3: Read final report**

Run:
```bash
cat "$ACCEPT_OUT/final-report.md"
```

Save the contents — input to Task 12 (runbook).

- [ ] **Step 4: No commit** (these are run artifacts under `/tmp/`, not the repo)

---

## Task 12: Write the runbook

**Files:**
- Create: `docs/orchestrator-e2e-petclinic.md`

- [ ] **Step 1: Author the runbook with actual numbers**

Substitute the placeholders `{{iter-N-branch}}`, `{{iter-N-newly}}`, etc. with the actual numbers captured in Tasks 10 and 11.

Exact content (template):

````markdown
# Orchestrator E2E — spring-petclinic

End-to-end runbook for driving the merged 6-stage orchestrator against the real
[spring-petclinic](https://github.com/spring-projects/spring-petclinic) SUT with
Postgres in docker, JaCoCo coverage measured against petclinic's production code,
and the coverage-feedback loop iterating up to 5 times.

## What this proves

The acceptance is a live run that:
- Compiles ≥ 1 RestAssured test inside petclinic's source tree and runs it.
- Produces a valid `jacoco.xml` that feeds Stage 6's `CoverageDeltaCalculator`.
- Terminates either by reaching the 70 % branch-coverage target, by the
  no-progress heuristic, or by exhausting `--max-iterations 5`.
- Emits `final-report.md` with per-iter coverage numbers.

## Prereqs (one-time)

```bash
# 1) Clone petclinic (pinned to e4bebf2 for reproducibility)
git clone https://github.com/spring-projects/spring-petclinic.git \
  ~/github_spring-petclinic/spring-petclinic
git -C ~/github_spring-petclinic/spring-petclinic checkout e4bebf2

# 2) Build petclinic SUT jar
cd ~/github_spring-petclinic/spring-petclinic && mvn -DskipTests package

# 3) Clone the private jdbc-intercept-agent + publish to Maven Local
git clone https://github.com/baekchangjoon/jdbc-intercept-agent.git \
  ~/github_jdbc-intercept-agent/jdbc-intercept-agent
cd ~/github_jdbc-intercept-agent/jdbc-intercept-agent && ./gradlew publishToMavenLocal

# 4) Publish graph-rag bridges to Maven Local
cd ~/graph-rag/graph-rag
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true \
    :shared-model:publishToMavenLocal \
    :graph-rag-builder:publishToMavenLocal

# 5) Build orchestrator + scout-launcher + test-generator binaries
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true \
    :orchestrator:installDist \
    :scout-launcher:installDist \
    :test-generator:installDist
```

## Per-run setup

```bash
# Bring Postgres + Redis + Kafka up (containers stay up across iterations —
# template.yml sets teardown-on-exit: false).
docker compose -f samples/scout/petclinic/docker-compose.yml up -d
```

## Run

```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./orchestrator/build/install/orchestrator/bin/orchestrator \
    --sut-source            "$HOME/github_spring-petclinic/spring-petclinic/src/main/java" \
    --project               petclinic \
    --test-package          com.example.petclinic.tests \
    --scout-config-template samples/scout/petclinic/template.yml \
    --scout-base-url        http://localhost:8084 \
    --out                   /tmp/graph-rag-petclinic-e2e \
    --coverage-target       0.70 \
    --max-iterations        5 \
    --scout-launcher-bin    ./scout-launcher/build/install/scout-launcher/bin/scout-launcher \
    --test-generator-bin    ./test-generator/build/install/test-generator/bin/test-generator \
    --user-test-command     ./scripts/petclinic-stage5.sh
```

Wall-clock: ~8-15 min for 5 iters.

## Results from the reference run (date: {{run-date}})

| iter | branch | newly_covered | still_missing | terminate | reason |
|------|--------|---------------|---------------|-----------|--------|
| 1    | {{i1-branch}} | {{i1-new}} | {{i1-miss}} | {{i1-term}} | {{i1-reason}} |
| 2    | {{i2-branch}} | {{i2-new}} | {{i2-miss}} | {{i2-term}} | {{i2-reason}} |
| 3    | {{i3-branch}} | {{i3-new}} | {{i3-miss}} | {{i3-term}} | {{i3-reason}} |
| 4    | {{i4-branch}} | {{i4-new}} | {{i4-miss}} | {{i4-term}} | {{i4-reason}} |
| 5    | {{i5-branch}} | {{i5-new}} | {{i5-miss}} | {{i5-term}} | {{i5-reason}} |

Endpoints covered: {{covered-endpoint-list}}
Endpoints quarantined by scout-launcher: {{quarantined-list}}
Manual-review queue contents: {{manual-review}}

## Teardown

```bash
docker compose -f samples/scout/petclinic/docker-compose.yml down
```

## Known limitations

1. The static path explorer is boundary-value-only (no `@NotNull`, enum
   permutations, or `@ExceptionHandler` heuristics yet — see prior session's
   spec §1.2). Later iters tend to regenerate the same paths unless Stage 6's
   `excludePaths` prunes them; this is why the wrapper accumulates tests across
   iters so coverage grows monotonically.
2. scout-launcher's strict-mode quarantines endpoints whose live status differs
   from the predicted `expected-status`. These show up under
   `iter-N/stage3-archive/quarantine/` instead of `iter-N/stage3-archive/<id>/`
   and are silently skipped by Stage 4 — they contribute no tests.
3. Every gradle command needs `-Pagent.enabled=true`; see handoff §7. Fixing
   this is a future stretch goal.
4. The `jdbc-intercept-agent` repo is private. Reproducers need access plus
   the Maven Local publish step above.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `Stage 1 produced zero endpoints` | `--sut-source` doesn't point at `src/main/java` | check the absolute path |
| Stage 3 hangs on health check | Postgres not up | `docker compose ps` |
| Stage 5 fails: `expected JaCoCo XML at …` | mvn failed; injected test didn't compile | `cd ~/github_spring-petclinic/spring-petclinic && mvn -q -DskipITs test` to repro |
| Coverage drops between iters | Wrapper failed to glob a prior iter | check `iter-*/stage4-tests/` exist with `.java` files |
````

- [ ] **Step 2: Verify it renders cleanly**

Run:
```bash
ls docs/orchestrator-e2e-petclinic.md && head -30 docs/orchestrator-e2e-petclinic.md
```

Expected: file exists; header looks right.

- [ ] **Step 3: Commit**

```bash
git add docs/orchestrator-e2e-petclinic.md
git commit -m "docs(orchestrator): real-petclinic E2E runbook with reference-run numbers"
```

---

## Task 13: Final green check + branch push

**Files:**
- None. Verification + branch push only.

- [ ] **Step 1: Full check**

Run:
```bash
JAVA_HOME=/Users/changjoonbaek/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home \
  ./gradlew -Pagent.enabled=true check
```

Expected: BUILD SUCCESSFUL. In particular `:graph-rag-builder:test` (171 GREEN, 3 Neo4j-required skipped) and `:orchestrator:test` (now with the 6 new tests added by this plan) all pass.

- [ ] **Step 2: Confirm commit history**

Run:
```bash
git log --oneline a89d662..HEAD
```

Expected: roughly 9-10 commits — the spec from earlier (`a89d662`) plus this plan's task commits. Order should be (test-red → impl → commit) pairs followed by the runbook.

- [ ] **Step 3: Push branch + open PR**

Run:
```bash
git push -u origin HEAD
gh pr create --title "feat: real-petclinic E2E — wiring fixes + Stage 5 wrapper + runbook" \
  --body "$(cat <<'EOF'
## Summary
- Two orchestrator wiring fixes that make the merged 6-stage loop actually compose
  against a real Spring SUT: per-iteration `output.archive-dir` rewrite and Stage 5
  arg passthrough (spec §2.2 + §2.3).
- New `samples/scout/petclinic/template.yml` and `scripts/petclinic-stage5.sh` —
  the Stage 5 wrapper accumulates ALL prior + current iters' generated tests so
  branch coverage grows monotonically.
- New runbook `docs/orchestrator-e2e-petclinic.md` with numbers from one live
  5-iter run against `spring-petclinic@e4bebf2`.

## Test plan
- [ ] `:orchestrator:test` GREEN, including the 6 new tests (Shell arg passthrough,
      archive-dir rewrite, wrapper happy + 3 error paths)
- [ ] `:graph-rag-builder:test` GREEN (no regressions; nothing touched there)
- [ ] `./gradlew -Pagent.enabled=true check` GREEN
- [ ] Live 5-iter run completes without crash and produces `final-report.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL printed. Confirm CI passes.

---

## Self-review notes

Spec coverage check:
- §1.1 G1 (orchestrator runs E2E) → Tasks 10, 11.
- §1.1 G2 (cumulative coverage) → Task 7 (wrapper) + Task 6/8 (tests prove cumulative behavior).
- §1.1 G3 (two wiring fixes) → Tasks 1-4.
- §1.1 G4 (runbook) → Task 12.
- §2.1 (template.yml) → Task 5.
- §2.2 (archive-dir patch) → Tasks 3-4.
- §2.3 (Stage 5 args) → Tasks 1-2.
- §3.2 (wrapper contract) → Tasks 6-8.
- §4 acceptance criteria → covered by Tasks 9 (installDist), 10/11 (live run), 13 (full check).

Placeholder scan: only the runbook's `{{run-date}}` / `{{iN-…}}` markers, which are
explicitly the post-run substitution points called out in Task 12 Step 1.

Type/symbol consistency:
- `Shell.runTestsAndJacoco` signature unchanged (only body changes) — Tasks 1, 2, 8 match.
- `IterationRunner.runOne` signature unchanged (only adds 7 lines inside the method) — Tasks 3, 4 match.
- `OrchestratorConfig` constructor used in Task 3 matches its actual record signature (8 fields, exact order verified against `OrchestratorConfig.java`).
- `ExternalStageRunner.Shell(scoutBin, tgBin, userTestCommand)` constructor in Task 1 matches the real one (`Path, Path, List<String>`).
