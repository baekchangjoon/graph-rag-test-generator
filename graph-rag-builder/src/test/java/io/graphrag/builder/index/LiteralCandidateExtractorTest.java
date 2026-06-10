package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiteralCandidateExtractorTest {

    @Test
    void extract_collectsEnumStyleLiteralsOnly() {
        List<String> literals = new LiteralCandidateExtractor().extract(
                Path.of("src/test/resources/sample-src"),
                "io.graphrag.sample.orders.OrderController");

        assertThat(literals).contains("EXPRESS", "PENDING");
        // 공백 포함 메시지 문자열은 enum-스타일이 아니므로 제외
        assertThat(literals).noneMatch(l -> l.contains(" "));
        // 결정성: 정렬되어 있다
        assertThat(literals).isSorted();
    }

    @Test
    void extract_unknownClass_returnsEmpty() {
        assertThat(new LiteralCandidateExtractor().extract(
                Path.of("src/test/resources/sample-src"), "com.example.Nope")).isEmpty();
    }
}
