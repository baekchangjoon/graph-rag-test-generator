package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderCliUsageTest {
    @Test
    void documentsListGlobMix() {
        String u = BuilderCli.usage();
        assertTrue(u.contains("--sut-src"));
        assertTrue(u.contains("--endpoint"));
        assertTrue(u.contains("{"), "brace glob 예시 포함");
        assertTrue(u.contains("**"), "재귀 glob 문법 포함");
        assertTrue(u.toLowerCase().contains("glob"));
    }
}
