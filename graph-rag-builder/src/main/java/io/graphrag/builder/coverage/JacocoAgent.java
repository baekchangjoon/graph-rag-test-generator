package io.graphrag.builder.coverage;

import org.jacoco.agent.AgentJar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;

/** jacoco agent jar를 추출하고 SUT 부착용 JAVA_TOOL_OPTIONS를 만든다 (SUT 무수정). */
public final class JacocoAgent {

    private final Path agentJar;
    private final int tcpPort;

    private JacocoAgent(Path agentJar, int tcpPort) {
        this.agentJar = agentJar;
        this.tcpPort = tcpPort;
    }

    public static JacocoAgent prepare(Path workDir) {
        try {
            Path jar = workDir.resolve("jacocoagent.jar");
            AgentJar.extractTo(jar.toFile());
            try (ServerSocket socket = new ServerSocket(0)) {
                return new JacocoAgent(jar, socket.getLocalPort());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to prepare jacoco agent", e);
        }
    }

    public String javaToolOptions() {
        return "-javaagent:" + agentJar.toAbsolutePath()
                + "=output=tcpserver,address=127.0.0.1,port=" + tcpPort;
    }

    public int tcpPort() {
        return tcpPort;
    }
}
