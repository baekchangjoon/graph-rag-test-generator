package io.graphrag.builder.poc.fanout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** PoC: -Dpjacoco.agent.jar 로 받은 pjacoco agent를 SUT 부착용 JVM 옵션으로 만든다 (메인 build 무수정). */
public final class PjacocoAgent {
    private final Path agentJar;

    private PjacocoAgent(Path agentJar) { this.agentJar = agentJar; }

    public static PjacocoAgent fromSystemProperty() {
        String p = System.getProperty("pjacoco.agent.jar");
        if (p == null || p.isBlank()) {
            throw new IllegalStateException("system property pjacoco.agent.jar not set "
                + "(run e2e/poc-fanout/install-pjacoco.sh and pass -Dpjacoco.agent.jar=<path>)");
        }
        Path jar = Paths.get(p);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("pjacoco.agent.jar is not a file: " + jar);
        }
        return new PjacocoAgent(jar);
    }

    public Path agentJar() { return agentJar; }

    public String javaToolOptions(Path destfileDir, int controlPort, String includes, boolean traceKeyAutoCreate) {
        return optionString("-javaagent:" + agentJar.toAbsolutePath(), destfileDir.toAbsolutePath().toString(),
                controlPort, includes, traceKeyAutoCreate);
    }

    public String containerJavaToolOptions(String mountPath, Path destfileDir, int controlPort,
                                           String includes, boolean traceKeyAutoCreate) {
        return optionString("-javaagent:" + mountPath, destfileDir.toString(), controlPort, includes, traceKeyAutoCreate);
    }

    private static String optionString(String agentArg, String dest, int port, String includes, boolean traceKey) {
        StringBuilder sb = new StringBuilder(agentArg)
                .append("=destfile=").append(dest)
                .append(",port=").append(port)
                .append(",includes=").append(includes);
        if (traceKey) sb.append(",traceKeyAutoCreate=true");
        return sb.toString();
    }
}
