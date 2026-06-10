package io.graphrag.builder.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * SUT 운영 jar를 자식 프로세스로 기동 (SUT 소스 무수정 원칙, docs/01).
 * Hibernate SQL 로깅은 env 주입으로만 활성화하고 stdout을 파일로 캡처한다.
 */
public final class SutProcess {

    private static final Logger log = LoggerFactory.getLogger(SutProcess.class);
    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(90);

    private final Process process;
    private final Path logFile;
    private final int port;

    private SutProcess(Process process, Path logFile, int port) {
        this.process = process;
        this.logFile = logFile;
        this.port = port;
    }

    public static SutProcess start(Path sutJar, Path workDir, String jdbcUrl,
                                   String dbUser, String dbPass) {
        return start(sutJar, workDir, jdbcUrl, dbUser, dbPass, SutOptions.none());
    }

    public static SutProcess start(Path sutJar, Path workDir, String jdbcUrl,
                                   String dbUser, String dbPass, SutOptions options) {
        try {
            Files.createDirectories(workDir);
            Path logFile = workDir.resolve("sut.log");
            int port = freePort();

            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(javaBin, "-jar", sutJar.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            builder.environment().putAll(java.util.Map.of(
                    "SERVER_PORT", String.valueOf(port),
                    "SPRING_DATASOURCE_URL", jdbcUrl,
                    "SPRING_DATASOURCE_USERNAME", dbUser,
                    "SPRING_DATASOURCE_PASSWORD", dbPass,
                    "DDL_AUTO", "create",
                    // LOGGING_LEVEL_* env는 로거 이름의 대소문자를 잃는다
                    // (org.hibernate.SQL은 case-sensitive) → JSON으로 주입
                    "SPRING_APPLICATION_JSON", loggingJson(options.extraLogLevels())));
            if (!options.javaToolOptions().isBlank()) {
                builder.environment().put("JAVA_TOOL_OPTIONS", options.javaToolOptions());
            }
            builder.environment().putAll(options.extraEnv());

            Process process = builder.start();
            SutProcess sut = new SutProcess(process, logFile, port);
            sut.awaitHealthy();
            log.info("SUT up on port {} (log: {})", port, logFile);
            return sut;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start SUT: " + sutJar, e);
        }
    }

    private void awaitHealthy() {
        HttpClient client = HttpClient.newHttpClient();
        URI health = URI.create(baseUri() + "/actuator/health");
        Instant deadline = Instant.now().plus(BOOT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException("SUT process died during boot; log: " + logFile);
            }
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(health).GET()
                                .timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("UP")) {
                    return;
                }
            } catch (IOException | InterruptedException ignored) {
                // 아직 부팅 중
            }
            sleep(500);
        }
        stop();
        throw new IllegalStateException("SUT did not become healthy in " + BOOT_TIMEOUT);
    }

    public String baseUri() {
        return "http://localhost:" + port;
    }

    public String readLog() {
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 현재 로그 길이. 캡처 구간 마커로 사용. */
    public long logOffset() {
        try {
            return Files.exists(logFile) ? Files.size(logFile) : 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readLogFrom(long offset) {
        String all = readLog();
        return offset >= all.length() ? "" : all.substring((int) offset);
    }

    /** [start, end) 바이트 구간의 로그. 입력별 캡처 구간 분리용. */
    public String readLogRange(long start, long end) {
        String all = readLog();
        int from = (int) Math.min(start, all.length());
        int to = (int) Math.min(end, all.length());
        return from >= to ? "" : all.substring(from, to);
    }

    public void stop() {
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String loggingJson(java.util.Map<String, String> extraLevels) {
        StringBuilder json = new StringBuilder("{\"logging.level.org.hibernate.SQL\":\"DEBUG\",")
                .append("\"logging.level.org.hibernate.orm.jdbc.bind\":\"TRACE\"");
        extraLevels.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> json.append(",\"logging.level.").append(e.getKey())
                        .append("\":\"").append(e.getValue()).append("\""));
        return json.append("}").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
