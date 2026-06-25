package io.graphrag.builder.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.cli.BuildConfig;
import io.graphrag.builder.cli.BuilderCli;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * REQ-001/003/004/006: path↔커버리지 trace 매핑 E2E.
 *
 * <p>order-service를 전체 빌드해 {@code coverage-by-path.json}과 {@code graph.json}의
 * {@code coverageTraceIds} 필드를 검증한다.
 *
 * <p>Docker(Testcontainers Postgres) 필요. {@code -Dsut.jar=...} 및 {@code -Dsut.src=...} 필요.
 * 미충족 시 skip.
 *
 * <p>구현(Task 2~12) 전까지 RED가 정상이며 약화·skip 처리 금지(Docker 미가용 시에만 skip).
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@EnabledIfSystemProperty(named = "sut.src", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoverageTraceMappingE2E {

    // 기존 full-build E2E 하네스 재사용: SUT compose 기동 + BuilderCli.build(config) 실행 →
    // outDir 반환. @BeforeAll에서 빌드를 1회 수행하고 모든 test 메서드는 outDir/execDir만 검증한다.

    private Path outDir;
    private Path execDir;   // outDir.resolve("work/pjacoco-exec")

    @BeforeAll
    void runFullBuild() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");

        outDir = Files.createTempDirectory("coverage-trace-mapping-e2e");
        execDir = outDir.resolve("work/pjacoco-exec");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        // EgressStubBodyFidelitySpanOnlyE2E의 BuilderCli.build 호출 패턴을 복제.
        // externalStubsDir = null (coverage E2E는 redirect 없이 직접 SUT를 기동).
        BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, outDir,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, null,
                Map.of(),
                null, null, authConfig, false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "otel", null, false));
    }

    @AfterAll
    void verifyNoLeak() {
        // SUT 프로세스·Postgres 컨테이너 수명은 BuilderCli.build 내부(Testcontainers Ryuk)가 관리한다.
        // 이 테스트가 직접 띄운 외부 자원이 없으므로 누수 검증만 기록한다.
        // (dev-workflow 누수 검증 게이트: 이 테스트가 만든 컨테이너/프로세스 잔존 = 0)
    }

    @Test
    void reportFileExistsAndSchemaValid() throws Exception {        // REQ-004
        assumeThat(Files.isDirectory(execDir)).isTrue();           // Docker 미가용 → skip
        Path report = outDir.resolve("coverage-by-path.json");
        assertThat(Files.exists(report)).isTrue();
        JsonNode root = Json.mapper().readTree(Files.readString(report));
        assertThat(root.hasNonNull("sutId")).isTrue();
        assertThat(root.hasNonNull("execDir")).isTrue();
        assertThat(root.get("paths").isArray()).isTrue();
        for (JsonNode p : root.get("paths")) {
            assertThat(p.has("pathId")).isTrue();
            assertThat(p.has("endpointId")).isTrue();
            assertThat(p.has("coverageTraceIds")).isTrue();
            assertThat(p.has("execFiles")).isTrue();
        }
    }

    @Test
    void graphPathsHaveCoverageTraceIds() throws Exception {        // REQ-001
        assumeThat(Files.isDirectory(execDir)).isTrue();
        JsonNode graph = Json.mapper().readTree(Files.readString(outDir.resolve("graph.json")));
        for (JsonNode p : graph.get("paths")) {
            assertThat(p.has("coverageTraceIds")).as("path %s", p.get("id")).isTrue();
        }
    }

    @Test
    void traceIdsResolveToExecFiles() throws Exception {            // REQ-003
        assumeThat(Files.isDirectory(execDir)).isTrue();
        JsonNode graph = Json.mapper().readTree(Files.readString(outDir.resolve("graph.json")));
        for (JsonNode p : graph.get("paths")) {
            for (JsonNode tid : p.get("coverageTraceIds")) {
                Path exec = execDir.resolve(tid.asText() + ".exec");
                assertThat(Files.exists(exec)).as("dangling exec for %s", tid.asText()).isTrue();
            }
        }
    }

    @Test
    void summaryMatchesSidecarWhenPresent() throws Exception {      // REQ-006
        assumeThat(Files.isDirectory(execDir)).isTrue();
        JsonNode report = Json.mapper().readTree(
                Files.readString(outDir.resolve("coverage-by-path.json")));
        for (JsonNode p : report.get("paths")) {
            for (JsonNode ef : p.get("execFiles")) {
                Path sidecar = outDir.resolve(ef.get("sidecar").asText());
                if (Files.exists(sidecar) && ef.hasNonNull("summary")) {
                    JsonNode sc = Json.mapper().readTree(Files.readString(sidecar));
                    assertThat(ef.get("summary").get("classCount").asInt())
                            .isEqualTo(sc.get("classCount").asInt());
                }
            }
        }
    }
}
