package io.graphrag.builder.coverage;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class JacocoAgentOptionsTest {
    @Test
    void containerOptionsBindAllInterfacesAtMountPath() {
        String opts = JacocoAgent.containerJavaToolOptions("/grb-agents/jacocoagent.jar", 6300);
        assertTrue(opts.contains("-javaagent:/grb-agents/jacocoagent.jar="));
        assertTrue(opts.contains("output=tcpserver"));
        assertTrue(opts.contains("address=*"));
        assertTrue(opts.contains("port=6300"));
    }
}
