package io.graphrag.builder.arch;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class NoLlmDependencyTest {
    @Test
    void indexers_haveNoLlmOrDirectHttpClientImports() throws Exception {  // REQ-021
        Path idx = Path.of("src/main/java/io/graphrag/builder/index");
        try (Stream<Path> files = Files.walk(idx)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String src = readString(p);
                assertThat(src)
                    .as("%s must not import LLM/network clients", p)
                    .doesNotContain("anthropic").doesNotContain("openai")
                    .doesNotContain("java.net.http.HttpClient")
                    .doesNotContain("okhttp3");
            });
        }
    }
    private static String readString(Path p) {
        try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
