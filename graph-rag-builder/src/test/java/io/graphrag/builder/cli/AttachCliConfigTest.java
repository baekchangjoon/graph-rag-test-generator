package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import java.util.Map;
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

    @Test void coveragePortPrefersNewFlag() {
        // --coverage-port 우선 (둘 다 있으면 신규 플래그)
        assertEquals("16300", BuilderCli.coveragePortOption(
                Map.of("--coverage-port", "16300", "--jacoco-port", "9999")));
        // --coverage-port 단독
        assertEquals("16300", BuilderCli.coveragePortOption(Map.of("--coverage-port", "16300")));
    }

    @Test void coveragePortAcceptsDeprecatedJacocoAlias() {
        // --jacoco-port 단독 → deprecated alias로 수락(REQ-P010 비파괴)
        assertEquals("16300", BuilderCli.coveragePortOption(Map.of("--jacoco-port", "16300")));
    }

    @Test void coveragePortMissingThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> BuilderCli.coveragePortOption(Map.of()));
    }
}
