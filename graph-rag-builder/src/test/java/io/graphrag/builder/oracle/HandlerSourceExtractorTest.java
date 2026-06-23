package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HandlerSourceExtractorTest {
    private static final Path SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extractsMethodBody() {  // REQ-008
        var ex = new HandlerSourceExtractor(SRC);
        String body = ex.extract("io.graphrag.sample.validation.ValidatedController", "create");
        assertThat(body).isNotBlank();
        assertThat(body).contains("startsWith");
    }

    @Test
    void missingMethodReturnsEmpty() {  // REQ-008
        var ex = new HandlerSourceExtractor(SRC);
        assertThat(ex.extract("io.graphrag.sample.validation.ValidatedController", "noSuch"))
                .isEmpty();
    }
}
