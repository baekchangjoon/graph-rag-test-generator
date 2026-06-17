package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttachCliConfigTest {
    @Test void attachConfigParsedFromArgs() throws Exception {
        var opts = BuilderCli.parseArgs(new String[]{"build",
                "--sut-src","/s","--out","/o","--sut-compose","/c.yml",
                "--attach","--app-service","app","--app-port","58080",
                "--jacoco-port","16300","--jdbc-url","jdbc:postgresql://localhost:55432/app"});
        assertTrue(opts.containsKey("--attach"));
        assertEquals("app", opts.get("--app-service"));
        assertEquals("58080", opts.get("--app-port"));
    }
}
