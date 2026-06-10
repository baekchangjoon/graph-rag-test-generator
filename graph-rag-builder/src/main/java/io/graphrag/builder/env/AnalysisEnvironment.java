package io.graphrag.builder.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 도구 1의 분석 환경 (docs/03): Testcontainers Postgres + SUT 운영 jar 외부 프로세스.
 * 테스트 실행 환경(docs/06)과는 별개다.
 */
public class AnalysisEnvironment implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEnvironment.class);

    private final PostgreSQLContainer<?> postgres;
    private SutProcess sut;

    public AnalysisEnvironment(String postgresImage) {
        // Docker Engine 29+: docker-java의 구버전 API(1.32) 호출이 거부되므로 명시
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
        this.postgres = new PostgreSQLContainer<>(postgresImage)
                .withDatabaseName("app").withUsername("app").withPassword("app");
    }

    public void start(Path sutJar, Path workDir) {
        start(sutJar, workDir, SutOptions.none());
    }

    public void start(Path sutJar, Path workDir, SutOptions options) {
        log.info("starting analysis postgres...");
        postgres.start();
        log.info("starting SUT process: {}", sutJar);
        sut = SutProcess.start(sutJar, workDir, jdbcUrl(), "app", "app", options);
    }

    public String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), "app", "app");
    }

    public SutProcess sut() {
        return sut;
    }

    @Override
    public void close() {
        if (sut != null) {
            sut.stop();
        }
        postgres.stop();
    }
}
