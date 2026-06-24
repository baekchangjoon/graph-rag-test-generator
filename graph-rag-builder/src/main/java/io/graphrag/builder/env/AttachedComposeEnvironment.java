package io.graphrag.builder.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * attach 모드 환경 (decision A): 사용자 compose + 생성된 override 를 빌더가 up/down 한다.
 * SQL 캡처는 app 서비스 컨테이너 로그를 파일로 흘려 ContainerSut가 byte 슬라이스로 읽는다.
 * 커버리지는 published pjacoco control 포트로 PjacocoCoverageBackend가 회수한다(P1-6에서 jacoco 제거).
 */
public final class AttachedComposeEnvironment implements ExplorationEnvironment {

    private static final Logger log = LoggerFactory.getLogger(AttachedComposeEnvironment.class);

    /**
     * @param appBaseUri    호스트에서 본 app URL (예: http://localhost:58080)
     * @param jdbcUrl       호스트에서 본 DB JDBC URL (published DB 포트)
     * @param coverageHost  pjacoco control dump host (보통 localhost)
     * @param coveragePort  pjacoco control published 포트
     * @param kafkaBootstrap nullable — --kafka-bootstrap 미지정 시 null
     */
    public record Config(Path userCompose, Path overrideCompose, String appService, String projectName,
                         String appBaseUri, String jdbcUrl, String dbUser, String dbPass,
                         String coverageHost, int coveragePort, String kafkaBootstrap,
                         String healthPath, int readyTimeoutSeconds, List<String> captureServices) {
        public Config {
            captureServices = (captureServices == null || captureServices.isEmpty())
                    ? List.of(appService) : List.copyOf(captureServices);
        }

        /** 기존 13-arg 호출부 호환 (captureServices 미지정 → [appService]). */
        public Config(Path userCompose, Path overrideCompose, String appService, String projectName,
                      String appBaseUri, String jdbcUrl, String dbUser, String dbPass,
                      String coverageHost, int coveragePort, String kafkaBootstrap,
                      String healthPath, int readyTimeoutSeconds) {
            this(userCompose, overrideCompose, appService, projectName, appBaseUri, jdbcUrl, dbUser, dbPass,
                    coverageHost, coveragePort, kafkaBootstrap, healthPath, readyTimeoutSeconds, List.of());
        }
    }

    private final Config config;
    private final DbConfig.Type dbType;
    // OTEL SQL 캡처 모드에서만 non-null. 호스트에 떠 있는 리시버를 runAttached가 만들어 주입하며,
    // 이 환경이 생명주기를 소유(close 시 stop)한다. log 모드면 null → explore()가 LogParserCapture 폴백.
    private final io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver;
    // 외부 HTTP(스텁/실서비스) 캡처 모드에서만 non-null. runAttached가 만들어 주입하며 이 환경이 생명주기를 소유(close 시 close).
    private final HttpCaptureServer httpCapture;
    private ContainerSut sut;
    private Process logTail;

    public AttachedComposeEnvironment(Config config, DbConfig.Type dbType) {
        this(config, dbType, null, null);
    }

    public AttachedComposeEnvironment(Config config, DbConfig.Type dbType,
                                      io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver) {
        this(config, dbType, otlpReceiver, null);
    }

    public AttachedComposeEnvironment(Config config, DbConfig.Type dbType,
                                      io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver,
                                      HttpCaptureServer httpCapture) {
        this.config = config;
        this.dbType = dbType;
        this.otlpReceiver = otlpReceiver;
        this.httpCapture = httpCapture;
    }

    static List<String> baseCompose(Config c) {
        return new ArrayList<>(List.of("docker", "compose", "-p", c.projectName(),
                "-f", c.userCompose().toString(), "-f", c.overrideCompose().toString()));
    }
    static List<String> upCommand(Config c) {
        // capture-services 전체 기동(+depends_on): A→B→C가 떠야 C 로그가 발생한다.
        List<String> cmd = baseCompose(c); cmd.addAll(List.of("up", "-d", "--wait"));
        cmd.addAll(c.captureServices()); return cmd;
    }
    static List<String> downCommand(Config c) {
        List<String> cmd = baseCompose(c); cmd.addAll(List.of("down", "-v")); return cmd;
    }
    static List<String> logsCommand(Config c) {
        // 한 파일에 인터리브 tail. traceId가 상관 키이므로 --no-log-prefix 유지.
        List<String> cmd = baseCompose(c); cmd.addAll(List.of("logs", "--no-log-prefix", "-f"));
        cmd.addAll(c.captureServices()); return cmd;
    }

    /** compose up → app readiness 폴링(healthcheck 유무와 무관) → 로그 스트림 시작 → ContainerSut 구성. */
    public void start(Path workDir) {
        run(upCommand(config), "compose up");
        awaitAppReady();   // --wait는 healthcheck 없는 서비스를 기다리지 않으므로 직접 폴링(리뷰 CRITICAL)
        try {
            Path logFile = Files.createDirectories(workDir).resolve("attach-sut.log");
            Files.writeString(logFile, "");
            logTail = new ProcessBuilder(logsCommand(config))
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            sut = new ContainerSut(config.appBaseUri(), logFile, logTail);
            log.info("attached to compose project {} (app {})", config.projectName(), config.appBaseUri());
        } catch (IOException e) {
            throw new UncheckedIOException("attach 로그 스트림 시작 실패", e);
        }
    }

    /** SutProcess.awaitHealthy와 동등: <appBaseUri><healthPath> 가 2xx + "UP" 될 때까지 폴링. */
    private void awaitAppReady() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.URI health = java.net.URI.create(config.appBaseUri() + config.healthPath());
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(config.readyTimeoutSeconds());
        while (java.time.Instant.now().isBefore(deadline)) {
            try {
                var resp = client.send(java.net.http.HttpRequest.newBuilder(health).GET()
                                .timeout(java.time.Duration.ofSeconds(2)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("UP")) { return; }
            } catch (Exception ignored) { /* 아직 부팅 중 */ }
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("awaitAppReady 중단됨", e);
            }
        }
        throw new IllegalStateException("attach SUT가 " + config.readyTimeoutSeconds()
                + "s 내 ready 되지 않음: " + health);
    }

    private void run(List<String> cmd, String label) {
        try {
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException(label + " 실패 (exit " + code + "): " + String.join(" ", cmd));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(label + " 실행 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " 중단됨", e);
        }
    }

    @Override public SutHandle sut() { return sut; }
    @Override public io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver() { return otlpReceiver; }
    @Override public DbConfig.Type dbType() { return dbType; }
    @Override public HttpCaptureServer httpCapture() { return httpCapture; }   // nullable — 외부 HTTP 캡처 배선 시 non-null
    @Override public String kafkaBootstrapServers() { return config.kafkaBootstrap(); }
    @Override public String coverageHost() { return config.coverageHost(); }
    @Override public int coveragePort() { return config.coveragePort(); }

    @Override public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.jdbcUrl(), config.dbUser(), config.dbPass());
    }

    @Override public void close() {
        try {
            if (sut != null) { sut.stop(); }   // ContainerSut.stop(): destroy → waitFor → destroyForcibly
            if (otlpReceiver != null) { otlpReceiver.stop(); }
            if (httpCapture != null) { httpCapture.close(); }
        } finally {
            run(downCommand(config), "compose down");
        }
    }
}
