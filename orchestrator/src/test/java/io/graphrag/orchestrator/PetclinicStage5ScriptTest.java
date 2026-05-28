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
}
