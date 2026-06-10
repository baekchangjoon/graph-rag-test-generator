package io.graphrag.builder.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 도구 1의 분석 환경 (docs/03): Testcontainers Postgres + 임베디드 WireMock +
 * SUT 운영 jar 외부 프로세스. 테스트 실행 환경(docs/06)과는 별개다.
 */
public class AnalysisEnvironment implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEnvironment.class);

    /** sutEnv 값에서 임베디드 WireMock URL로 치환되는 자리표시자. */
    public static final String WIREMOCK_PLACEHOLDER = "{{wiremock}}";

    private final PostgreSQLContainer<?> postgres;
    private final HttpCaptureServer httpCapture = new HttpCaptureServer();
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
        start(sutJar, workDir, options, null, Map.of());
    }

    /**
     * @param externalStubsDir 외부 시스템의 minimal valid 응답 (WireMock mapping JSON)
     * @param sutEnvTemplate   SUT 외부 의존 redirect용 env. 값의 {{wiremock}}은 치환된다.
     */
    public void start(Path sutJar, Path workDir, SutOptions options,
                      Path externalStubsDir, Map<String, String> sutEnvTemplate) {
        log.info("starting analysis postgres...");
        postgres.start();
        httpCapture.start(externalStubsDir);

        Map<String, String> extraEnv = new LinkedHashMap<>(options.extraEnv());
        sutEnvTemplate.forEach((key, value) -> extraEnv.put(key,
                value.replace(WIREMOCK_PLACEHOLDER, httpCapture.baseUrl())));

        log.info("starting SUT process: {}", sutJar);
        sut = SutProcess.start(sutJar, workDir, jdbcUrl(), "app", "app",
                new SutOptions(options.javaToolOptions(), options.extraLogLevels(), extraEnv));
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

    public HttpCaptureServer httpCapture() {
        return httpCapture;
    }

    @Override
    public void close() {
        if (sut != null) {
            sut.stop();
        }
        httpCapture.close();
        postgres.stop();
    }
}
