package io.graphrag.builder.env;

import io.graphrag.builder.capture.zipkin.ZipkinSpanReceiver;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AttachedComposeEnvironmentTest {
    private AttachedComposeEnvironment.Config cfg() {
        return new AttachedComposeEnvironment.Config(
                Path.of("/p/docker-compose.yml"), Path.of("/p/.grb/override.yml"),
                "app", "grb-attach",
                "http://localhost:58080",
                "jdbc:postgresql://localhost:55432/app", "app", "app",
                "localhost", 16300, null, "/actuator/health", 120,
                List.of());   // captureServices 미지정 → [appService]로 정규화
    }
    private AttachedComposeEnvironment.Config multiCfg() {
        return new AttachedComposeEnvironment.Config(
                Path.of("/p/docker-compose.yml"), Path.of("/p/.grb/override.yml"),
                "a", "grb-attach",
                "http://localhost:58080",
                "jdbc:postgresql://localhost:55432/app", "app", "app",
                "localhost", 16300, null, "/actuator/health", 120,
                List.of("a", "b", "c"));
    }
    @Test void upCommandUsesBothFilesAndProjectNameAndWaitForAppServiceOnly() {
        List<String> cmd = AttachedComposeEnvironment.upCommand(cfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "up","-d","--wait","app"), cmd);
    }
    @Test void downCommandRemovesVolumes() {
        List<String> cmd = AttachedComposeEnvironment.downCommand(cfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "down","-v"), cmd);
    }
    @Test void logsCommandFollowsAppServiceNoPrefix() {
        List<String> cmd = AttachedComposeEnvironment.logsCommand(cfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "logs","--no-log-prefix","-f","app"), cmd);
    }
    @Test void logsCommandFollowsAllCaptureServicesNoPrefix() {
        List<String> cmd = AttachedComposeEnvironment.logsCommand(multiCfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "logs","--no-log-prefix","-f","a","b","c"), cmd);
    }
    @Test void upCommandWaitsForAllCaptureServices() {
        List<String> cmd = AttachedComposeEnvironment.upCommand(multiCfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "up","-d","--wait","a","b","c"), cmd);
    }

    @Test void zipkinReceiverNullByDefault() {
        var env = new AttachedComposeEnvironment(cfg(), DbConfig.Type.POSTGRES);
        assertNull(env.zipkinReceiver());
    }

    @Test void zipkinReceiverReturnsInjectedInstance() {
        // sleuth attach: runAttached가 ZipkinSpanReceiver를 주입하면 환경이 그대로 노출한다
        // (EgressCollector.forMode(env)가 이를 집어 otel과 동일한 egress 파이프라인을 탄다).
        var receiver = new ZipkinSpanReceiver();   // start() 불필요 — 동일성만 검증, close() 미호출(docker down 회피)
        var env = new AttachedComposeEnvironment(cfg(), DbConfig.Type.POSTGRES, null, null, receiver);
        assertSame(receiver, env.zipkinReceiver());
        assertNull(env.otlpReceiver());
    }
}
