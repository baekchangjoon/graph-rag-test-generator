package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointFieldSelectorTest {
    @Test
    void selectsPatternEmailStringSkipsNumeric() {  // REQ-006
        var fields = List.of(
                new BodyShape.BodyField("code", "java.lang.String"),
                new BodyShape.BodyField("contact", "java.lang.String"),
                new BodyShape.BodyField("quantity", "int"));
        var constraints = Map.of(
                "code", List.of(new FieldConstraint("code", Kind.PATTERN, 0, "[A-Z]{3}")),
                "contact", List.of(new FieldConstraint("contact", Kind.EMAIL, 0, null)));
        var sel = EndpointFieldSelector.select(fields, constraints);
        assertThat(sel.fields()).extracting(BodyShape.BodyField::name)
                .containsExactlyInAnyOrder("code", "contact");
        assertThat(sel.patternByField()).containsEntry("code", "[A-Z]{3}");
        assertThat(sel.emailFields()).containsExactly("contact");
    }

    @Test
    void selectsDomainCodeKeywordStringExcludesEnumType() {  // REQ-006
        var fields = List.of(
                new BodyShape.BodyField("status", "java.lang.String"),
                new BodyShape.BodyField("tier", "io.graphrag.sample.Tier"));   // enum 타입(비-String)
        var sel = EndpointFieldSelector.select(fields, Map.of());
        assertThat(sel.fields()).extracting(BodyShape.BodyField::name).containsExactly("status");
    }

    @Test
    void emptyWhenNothingStrict() {  // REQ-006
        var sel = EndpointFieldSelector.select(
                List.of(new BodyShape.BodyField("plainName", "java.lang.String")), Map.of());
        assertThat(sel.fields()).isEmpty();
    }
}
