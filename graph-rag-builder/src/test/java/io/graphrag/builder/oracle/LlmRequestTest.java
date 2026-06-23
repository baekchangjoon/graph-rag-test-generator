package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRequestTest {
    @Test
    void carriesBodyOnlySourceAndSeparatedFieldConstraints() {  // REQ-014
        var fields = List.of(new BodyShape.BodyField("couponCode", "java.lang.String"));
        var req = new LlmRequest("post-api-coupons", "if (couponCode.startsWith(\"GOLD\")) {}",
                fields, Map.of("couponCode", "[A-Z]{4}-\\d{4}"), Set.of(),
                "claude-haiku-4-5-20251001");
        assertThat(req.handlerSource()).doesNotContain("class ").doesNotContain("import ");
        assertThat(req.patternByField()).containsEntry("couponCode", "[A-Z]{4}-\\d{4}");
        assertThat(req.fields()).extracting(BodyShape.BodyField::name).containsExactly("couponCode");
    }

    @Test
    void emptyFieldValues() {
        assertThat(LlmFieldValues.empty().stringValuesByField()).isEmpty();
    }
}
