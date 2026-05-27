package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.DependenciesConfig.DockerCompose;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Wraps the {@code docker compose} CLI to spin dependencies up/down. */
public final class DockerComposeOrchestrator implements AutoCloseable {

    private final DockerCompose cfg;
    private final boolean managed;
    private boolean started = false;

    public DockerComposeOrchestrator(DockerCompose cfg) {
        this.cfg = cfg;
        this.managed = cfg != null;
    }

    /** Compose up + (optional) wait for healthy. No-op if no docker-compose section in config. */
    public void start() throws IOException, InterruptedException {
        if (!managed) return;
        Path composeFile = Paths.get(cfg.file()).toAbsolutePath();
        System.out.println("[scout] docker compose -f " + composeFile + " up -d");
        runOrThrow(composeCmd("up", "-d"));
        started = true;

        if (Boolean.TRUE.equals(cfg.waitForHealthy())) waitForHealthy();
    }

    private void waitForHealthy() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cfg.healthTimeoutSeconds());
        List<String> targets = cfg.services().isEmpty() ? listContainerIds() : cfg.services();
        while (System.currentTimeMillis() < deadline) {
            boolean allHealthy = true;
            for (String s : targets) {
                String state = inspectHealth(s);
                if (!"healthy".equals(state) && !"none".equals(state)) {
                    allHealthy = false;
                    break;
                }
            }
            if (allHealthy) {
                System.out.println("[scout] docker compose services healthy: " + targets);
                return;
            }
            Thread.sleep(2000);
        }
        throw new IOException("docker compose services did not become healthy within "
                + cfg.healthTimeoutSeconds() + "s: " + targets);
    }

    private List<String> listContainerIds() throws IOException, InterruptedException {
        Process p = new ProcessBuilder(composeCmd("ps", "-q"))
                .redirectErrorStream(true).start();
        List<String> out = new ArrayList<>();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.isBlank()) out.add(l.trim());
            }
        }
        p.waitFor();
        return out;
    }

    private String inspectHealth(String containerOrService) throws IOException, InterruptedException {
        // Try `docker inspect` first; fall back to compose ps for service names.
        Process p = new ProcessBuilder(
                "docker", "inspect", "-f", "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
                containerOrService)
                .redirectErrorStream(true).start();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line = r.readLine();
            p.waitFor();
            return line == null ? "unknown" : line.trim();
        }
    }

    @Override
    public void close() {
        if (!managed || !started || !Boolean.TRUE.equals(cfg.teardownOnExit())) return;
        try {
            System.out.println("[scout] docker compose down");
            runOrThrow(composeCmd("down"));
        } catch (Exception e) {
            System.err.println("[scout] docker compose down failed: " + e.getMessage());
        }
    }

    private List<String> composeCmd(String... args) {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", cfg.file()));
        for (String a : args) cmd.add(a);
        return cmd;
    }

    private static void runOrThrow(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int rc = p.waitFor();
        if (rc != 0) throw new IOException("command failed (exit " + rc + "): " + String.join(" ", cmd));
    }
}
