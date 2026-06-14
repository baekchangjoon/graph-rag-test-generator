package io.graphrag.builder.index;

import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationConstraintExtractorTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extract_readsJakartaConstraintsPerField() {
        Map<String, List<FieldConstraint>> result = new ValidationConstraintExtractor()
                .extract(SAMPLE_SRC, "io.graphrag.sample.validation.ValidatedRequest");

        assertThat(result.get("name")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.NOT_BLANK, Kind.SIZE_MIN, Kind.SIZE_MAX);
        assertThat(result.get("name")).filteredOn(c -> c.kind() == Kind.SIZE_MIN)
                .singleElement().extracting(FieldConstraint::numArg).isEqualTo(2L);
        assertThat(result.get("name")).filteredOn(c -> c.kind() == Kind.SIZE_MAX)
                .singleElement().extracting(FieldConstraint::numArg).isEqualTo(10L);

        assertThat(result.get("quantity")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.MIN, Kind.MAX);
        assertThat(result.get("quantity")).extracting(FieldConstraint::numArg)
                .containsExactly(1L, 100L);

        assertThat(result.get("price")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.POSITIVE);
        assertThat(result.get("contact")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.EMAIL);
        assertThat(result.get("code")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.PATTERN);
        assertThat(result.get("code")).singleElement()
                .extracting(FieldConstraint::strArg).isEqualTo("[A-Z]{3}");
    }

    @Test
    void extract_unknownType_returnsEmpty() {
        assertThat(new ValidationConstraintExtractor()
                .extract(SAMPLE_SRC, "io.graphrag.sample.validation.Nope")).isEmpty();
    }
}
