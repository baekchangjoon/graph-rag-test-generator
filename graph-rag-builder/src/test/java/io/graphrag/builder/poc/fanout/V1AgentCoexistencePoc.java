package io.graphrag.builder.poc.fanout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC gate V1: OTel→pjacoco 공존 부팅 + 바닐라 .exec — REQ-001.
 * 실행: POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V1AgentCoexistencePoc*'
 */
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class V1AgentCoexistencePoc {

    @Test
    @DisplayName("REQ-001: OTel→pjacoco 공존 부팅 + 바닐라 .exec (V1 게이트)")
    void v1_agentCoexistence_bootAndExec() throws Exception {
        // Gradle 테스트 워킹 디렉터리는 서브프로젝트 루트(graph-rag-builder/)이므로 ../ 로 repo 루트로 이동
        Path repoRoot = Paths.get("").toAbsolutePath().getParent();
        Path scriptDir = repoRoot.resolve("e2e/poc-fanout");
        Path script = scriptDir.resolve("v1-agent-coexistence.sh");

        assertThat(script).exists().isExecutable();

        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
        pb.directory(repoRoot.toFile()); // repo root
        pb.redirectErrorStream(true); // merge stderr into stdout — prevents stdout/stderr deadlock

        Process proc = pb.start();

        // capture merged stdout+stderr; "V1 PASS" appears on stdout so it is still present
        String stdout;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            stdout = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = proc.waitFor();

        System.out.println("[V1AgentCoexistencePoc] script stdout: " + stdout);
        System.out.println("[V1AgentCoexistencePoc] exit code: " + exitCode);

        assertThat(exitCode)
                .as("v1-agent-coexistence.sh must exit 0")
                .isEqualTo(0);
        assertThat(stdout)
                .as("stdout must contain 'V1 PASS'")
                .contains("V1 PASS");
    }
}
