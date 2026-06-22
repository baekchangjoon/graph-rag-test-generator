package io.graphrag.builder.poc.fanout;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PjacocoAgentTest {
    @Test
    void fromSystemProperty_resolvesAgentJar() throws Exception {
        Path fake = Files.createTempFile("pjacoco-agent", ".jar");
        System.setProperty("pjacoco.agent.jar", fake.toString());
        try {
            PjacocoAgent agent = PjacocoAgent.fromSystemProperty();
            assertThat(agent.agentJar()).isRegularFile();
            String jto = agent.javaToolOptions(fake.getParent(), 6310, "org.springframework.samples.*", true);
            assertThat(jto).contains("-javaagent:" + fake.toAbsolutePath());
            assertThat(jto).contains("destfile=").contains("port=6310")
                           .contains("includes=org.springframework.samples.*").contains("traceKeyAutoCreate=true");
        } finally {
            System.clearProperty("pjacoco.agent.jar");
        }
    }

    @Test
    void fromSystemProperty_missingProperty_throws() {
        System.clearProperty("pjacoco.agent.jar");
        assertThatThrownBy(PjacocoAgent::fromSystemProperty)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pjacoco.agent.jar");
    }
}
