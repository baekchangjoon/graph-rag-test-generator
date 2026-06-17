package io.graphrag.builder.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 도구 1의 분석 환경 (docs/03): Testcontainers DB + 임베디드 WireMock +
 * SUT 운영 jar 외부 프로세스. 테스트 실행 환경(docs/06)과는 별개다.
 */
public class AnalysisEnvironment implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEnvironment.class);

    /** sutEnv 값에서 임베디드 WireMock URL로 치환되는 자리표시자. */
    public static final String WIREMOCK_PLACEHOLDER = "{{wiremock}}";

    private final JdbcDatabaseContainer<?> db;
    private final DbConfig dbConfig;
    private final GenericContainer<?> redis;   // nullable — Redis 의존 SUT(예: auth-user)용
    private final KafkaContainer kafka;        // nullable — Kafka 의존 SUT(예: diary, mindgraph)용
    private final HttpCaptureServer httpCapture = new HttpCaptureServer();
    private SutProcess sut;

    public AnalysisEnvironment(DbConfig dbConfig) {
        this(dbConfig, false, false);
    }

    public AnalysisEnvironment(DbConfig dbConfig, boolean withRedis) {
        this(dbConfig, withRedis, false);
    }

    public AnalysisEnvironment(DbConfig dbConfig, boolean withRedis, boolean withKafka) {
        // Docker Engine 29+: docker-java의 구버전 API(1.32) 호출이 거부되므로 명시
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
        this.dbConfig = dbConfig;
        this.db = JdbcContainers.create(dbConfig);
        this.redis = withRedis
                ? new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
                : null;
        this.kafka = withKafka
                ? new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
                : null;
    }

    public DbConfig.Type dbType() {
        return dbConfig.type();
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
        log.info("starting analysis db ({})...", dbConfig.type());
        db.start();
        httpCapture.start(externalStubsDir);

        Map<String, String> extraEnv = new LinkedHashMap<>(options.extraEnv());
        sutEnvTemplate.forEach((key, value) -> extraEnv.put(key,
                value.replace(WIREMOCK_PLACEHOLDER, httpCapture.baseUrl())));

        if (redis != null) {
            redis.start();
            // Spring Boot 2.x: spring.redis.*, 3.x: spring.data.redis.* — 둘 다 주입해 호환
            extraEnv.put("SPRING_DATA_REDIS_HOST", redis.getHost());
            extraEnv.put("SPRING_DATA_REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
            extraEnv.put("SPRING_REDIS_HOST", redis.getHost());
            extraEnv.put("SPRING_REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
            log.info("started analysis redis at {}:{}", redis.getHost(), redis.getMappedPort(6379));
        }

        if (kafka != null) {
            kafka.start();
            extraEnv.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers());
            log.info("started analysis kafka at {}", kafka.getBootstrapServers());
        }

        log.info("starting SUT process: {}", sutJar);
        sut = SutProcess.start(sutJar, workDir, jdbcUrl(), db.getUsername(), db.getPassword(),
                new SutOptions(options.javaToolOptions(), options.extraLogLevels(), extraEnv,
                        options.javaHome()));
    }

    public String jdbcUrl() {
        return db.getJdbcUrl();
    }

    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    public SutHandle sut() {
        return sut;
    }

    public HttpCaptureServer httpCapture() {
        return httpCapture;
    }

    /** Kafka 부트스트랩 서버 (--with-kafka 일 때). 빌더의 KafkaProducer 발행용. null이면 미기동. */
    public String kafkaBootstrapServers() {
        return kafka == null ? null : kafka.getBootstrapServers();
    }

    @Override
    public void close() {
        try {
            if (sut != null) {
                sut.stop();
            }
            httpCapture.close();
            if (redis != null) {
                redis.stop();
            }
            if (kafka != null) {
                kafka.stop();
            }
        } finally {
            db.stop();
        }
    }
}
