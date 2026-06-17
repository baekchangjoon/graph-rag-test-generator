package io.graphrag.builder.env;

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
                "localhost", 16300, null, "/actuator/health", 120);
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
}
