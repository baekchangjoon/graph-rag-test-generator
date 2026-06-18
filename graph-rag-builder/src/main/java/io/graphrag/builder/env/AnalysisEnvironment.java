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
public class AnalysisEnvironment implements ExplorationEnvironment {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEnvironment.class);

    /** sutEnv 값에서 임베디드 WireMock URL로 치환되는 자리표시자. */
    public static final String WIREMOCK_PLACEHOLDER = "{{wiremock}}";

    private final JdbcDatabaseContainer<?> db;
    private final DbConfig dbConfig;
    private final GenericContainer<?> redis;   // nullable — Redis 의존 SUT(예: auth-user)용
    private final KafkaContainer kafka;        // nullable — Kafka 의존 SUT(예: diary, mindgraph)용
    private final HttpCaptureServer httpCapture = new HttpCaptureServer();
    private SutProcess sut;
    private String coverageHost = "localhost";
    private int coveragePort;
    private io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver;   // OTEL SQL 캡처 모드에서만

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

    @Override
    public DbConfig.Type dbType() {
        return dbConfig.type();
    }

    public void start(Path sutJar, Path workDir) {
        start(sutJar, workDir, SutOptions.none());
    }

    public void start(Path sutJar, Path workDir, SutOptions options) {
        start(sutJar, workDir, options, null, Map.of());
    }

    public void start(Path sutJar, Path workDir, SutOptions options,
                      Path externalStubsDir, Map<String, String> sutEnvTemplate) {
        start(sutJar, workDir, options, externalStubsDir, sutEnvTemplate, null, null);
    }

    /**
     * @param externalStubsDir 외부 시스템의 minimal valid 응답 (WireMock mapping JSON)
     * @param sutEnvTemplate   SUT 외부 의존 redirect용 env. 값의 {{wiremock}}은 치환된다.
     * @param otelAgent        non-null이면 OTEL SQL 캡처 모드 — OTLP receiver를 띄우고 SUT에 otlp export env를
     *                         주입하며 hibernate batch_size=0 으로 SUT를 기동한다. null이면 기존 동작(log 폴백).
     * @param otelServiceName  OTEL_SERVICE_NAME (otelAgent non-null일 때만 사용).
     */
    public void start(Path sutJar, Path workDir, SutOptions options,
                      Path externalStubsDir, Map<String, String> sutEnvTemplate,
                      io.graphrag.builder.coverage.OtelAgent otelAgent, String otelServiceName) {
        log.info("starting analysis db ({})...", dbConfig.type());
        db.start();
        httpCapture.start(externalStubsDir);

        Map<String, String> extraEnv = new LinkedHashMap<>(options.extraEnv());
        sutEnvTemplate.forEach((key, value) -> extraEnv.put(key,
                value.replace(WIREMOCK_PLACEHOLDER, httpCapture.baseUrl())));

        boolean otelSqlCapture = otelAgent != null;
        if (otelSqlCapture) {
            otlpReceiver = new io.graphrag.builder.capture.otlp.OtlpTraceReceiver();
            otlpReceiver.start();
            // SUT는 호스트 자식 프로세스(java -jar)이므로 127.0.0.1 endpoint로 export한다.
            extraEnv.putAll(otelAgent.otlpEnv(otelServiceName, otlpReceiver.endpoint()));
            log.info("OTEL SQL capture: otlp receiver at {}", otlpReceiver.endpoint());
        }

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
            // 빌더 발행이 consumer 파티션 할당보다 빨라도 누락 안 되게 earliest로 읽는다(offset race 예방).
            // 미설정 시 default(latest)면 부팅 직후 발행한 레코드를 consumer가 못 받아 SQL 캡처가 비어
            // 간헐 실패한다(CI 재현). e2e/docker-compose.yml과 동일한 이유.
            extraEnv.putIfAbsent("SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET", "earliest");
            log.info("started analysis kafka at {}", kafka.getBootstrapServers());
        }

        log.info("starting SUT process: {}", sutJar);
        sut = SutProcess.start(sutJar, workDir, jdbcUrl(), db.getUsername(), db.getPassword(),
                new SutOptions(options.javaToolOptions(), options.extraLogLevels(), extraEnv,
                        options.javaHome(), otelSqlCapture));
    }

    @Override
    public io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver() {
        return otlpReceiver;
    }

    public String jdbcUrl() {
        return db.getJdbcUrl();
    }

    @Override
    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    @Override
    public SutHandle sut() {
        return sut;
    }

    @Override
    public HttpCaptureServer httpCapture() {
        return httpCapture;
    }

    /** Kafka 부트스트랩 서버 (--with-kafka 일 때). 빌더의 KafkaProducer 발행용. null이면 미기동. */
    @Override
    public String kafkaBootstrapServers() {
        return kafka == null ? null : kafka.getBootstrapServers();
    }

    public void coverageEndpoint(String host, int port) {
        this.coverageHost = host;
        this.coveragePort = port;
    }

    @Override
    public String coverageHost() {
        return coverageHost;
    }

    @Override
    public int coveragePort() {
        return coveragePort;
    }

    @Override
    public void close() {
        try {
            if (sut != null) {
                sut.stop();
            }
            if (otlpReceiver != null) {
                otlpReceiver.stop();
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
