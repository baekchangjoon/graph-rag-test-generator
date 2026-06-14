package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnumConstantExtractorTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extract_topLevelAndNestedEnums_inDeclarationOrder() {
        Map<String, List<String>> m = new EnumConstantExtractor().extract(SAMPLE_SRC);
        assertThat(m.get("io.graphrag.sample.enums.Palette")).containsExactly("RED", "GREEN", "BLUE");
        // 중첩 enum: BodyShapeExtractor가 BodyField.javaType에 raw getQualifiedName()($ 구분)을 쓰므로
        // 추출기 키도 $ 유지(정합). top-level은 $ 없어 무관.
        assertThat(m.get("io.graphrag.sample.enums.Palette$Shade")).containsExactly("LIGHT", "DARK");
    }
}
