package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.SutConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches the SUT in its own JVM with configured agents/classpath/properties,
 * tails stdout to console, waits for health URL, and shuts down via SIGTERM on close.
 */
public final class SutProcessOrchestrator implements AutoCloseable {

    private final SutConfig cfg;
    private final String archiveDir;
    private Process process;
    private Thread outPump;

    public SutProcessOrchestrator(SutConfig cfg, String archiveDir) {
        this.cfg = cfg;
        this.archiveDir = archiveDir;
    }

    public void start() throws IOException, InterruptedException {
        List<String> cmd = buildCommand();
        System.out.println("[scout] launching SUT: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        process = pb.start();
        outPump = pumpToStdout(process, "[sut] ");
        waitForHealth();
    }

    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        for (String a : cfg.agents()) {
            cmd.add("-javaagent:" + Path.of(a).toAbsolutePath());
        }
        if (!cfg.bootClasspath().isEmpty()) {
            StringBuilder bcp = new StringBuilder();
            for (int i = 0; i < cfg.bootClasspath().size(); i++) {
                if (i > 0) bcp.append(java.io.File.pathSeparator);
                bcp.append(Path.of(cfg.bootClasspath().get(i)).toAbsolutePath());
            }
            cmd.add("-Xbootclasspath/a:" + bcp);
        }
        cmd.addAll(cfg.jvmArgs());
        // Inject the archive output dir so SUT-side wiring (bridge shutdown hook) can write it.
        cmd.add("-Dgraphrag.archive.output.dir=" + Path.of(archiveDir).toAbsolutePath());
        for (var e : cfg.systemProperties().entrySet()) {
            cmd.add("-D" + e.getKey() + "=" + e.getValue());
        }
        if (cfg.jar() != null) {
            cmd.add("-jar");
            cmd.add(Path.of(cfg.jar()).toAbsolutePath().toString());
        } else {
            cmd.add("-cp");
            StringBuilder cp = new StringBuilder();
            for (int i = 0; i < cfg.classpath().size(); i++) {
                if (i > 0) cp.append(java.io.File.pathSeparator);
                cp.append(Path.of(cfg.classpath().get(i)).toAbsolutePath());
            }
            cmd.add(cp.toString());
            cmd.add(cfg.mainClass());
        }
        cmd.addAll(cfg.args());
        return cmd;
    }

    private static String javaBinary() {
        String home = System.getProperty("java.home");
        return Path.of(home, "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void waitForHealth() throws IOException, InterruptedException {
        String url = cfg.healthCheck().url();
        if (url == null || url.isBlank()) {
            // No health URL → just give it a moment.
            Thread.sleep(3000);
            System.out.println("[scout] SUT health URL not configured; sleeping 3s");
            return;
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        long deadline = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(cfg.healthCheck().timeoutSeconds());
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("SUT process exited before becoming healthy");
            }
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(2))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 400) {
                    System.out.println("[scout] SUT health check OK (" + resp.statusCode() + ")");
                    return;
                }
            } catch (Exception ignored) {
                // not ready yet
            }
            Thread.sleep(cfg.healthCheck().intervalMillis());
        }
        throw new IOException("SUT did not become healthy at " + url + " within "
                + cfg.healthCheck().timeoutSeconds() + "s");
    }

    private static Thread pumpToStdout(Process p, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(prefix + line);
                }
            } catch (IOException ignored) { }
        }, "sut-stdout-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public void close() {
        if (process == null) return;
        if (process.isAlive()) {
            System.out.println("[scout] sending SIGTERM to SUT (pid " + process.pid() + ")");
            process.destroy();   // SIGTERM on Unix
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    System.err.println("[scout] SUT did not exit in 30s, killing forcibly");
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("[scout] SUT exited with code " + process.exitValue());
    }
}
