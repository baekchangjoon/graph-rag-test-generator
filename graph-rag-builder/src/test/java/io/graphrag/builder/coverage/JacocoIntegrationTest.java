package io.graphrag.builder.coverage;

import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** SUT에 jacoco agent를 env로 부착하고 요청 단위 분기 커버리지를 회수한다. Docker 필요. */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class JacocoIntegrationTest {

    @TempDir
    Path workDir;

    @Test
    void requestCoverage_isObservablePerDump() throws Exception {
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        JacocoAgent agent = JacocoAgent.prepare(workDir);

        try (AnalysisEnvironment env = new AnalysisEnvironment(
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"))) {
            env.start(sutJar, workDir, new SutOptions(agent.javaToolOptions(), java.util.Map.of()));

            CoverageClient client = new CoverageClient("localhost", agent.tcpPort());
            BranchCoverageAnalyzer analyzer = new BranchCoverageAnalyzer(sutJar);

            // 부팅까지의 커버리지를 리셋 후 기준점 확보
            client.dump(true);
            BranchCoverage before = analyzer.analyze(client.dump(true));

            // 요청 1회 (400 분기: 빈 body)
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(env.sut().baseUri() + "/api/orders"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);

            BranchCoverage after = analyzer.analyze(client.dump(true));

            assertThat(after.totalBranches()).isGreaterThan(0);
            assertThat(after.covered()).isNotEmpty();
            // 검증 분기가 OrderController에서 새로 도달했어야 한다
            assertThat(after.newlyCoveredAgainst(before.covered()))
                    .anyMatch(b -> b.classFqn().contains("OrderController"));
        }
    }
}
