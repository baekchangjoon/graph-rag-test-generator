package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmJsonTest {
    @Test
    void parsesDirectJson() {  // REQ-017
        var v = LlmJson.parseFields("{\"fields\":[{\"field\":\"code\",\"values\":[\"GOLD-1234\"]}]}");
        assertThat(v.stringValuesByField()).containsEntry("code", List.of("GOLD-1234"));
    }

    @Test
    void extractsFromWrappedOutputWithPreamble() {  // REQ-017 (CLI 래핑/서문 관용)
        String wrapped = "Here are the values:\n```json\n"
                + "{\"fields\":[{\"field\":\"code\",\"values\":[\"GOLD-1234\",\"GOLD-9999\"]}]}\n```\n";
        var v = LlmJson.parseFields(wrapped);
        assertThat(v.stringValuesByField().get("code")).containsExactly("GOLD-1234", "GOLD-9999");
    }

    @Test
    void extractsFromKiroStyleAnsiWrappedOutput() {  // REQ-017 (kiro-cli TUI 색코드/푸터)
        String esc = "";
        String kiro = esc + "[38;5;141m> " + esc + "[1mjson\n" + esc + "[38;5;10m"
                + "{\"fields\":[{\"field\":\"couponCode\",\"values\":[\"GOLD-1234\",\"SILV-5678\"]}]}\n"
                + esc + "[0m\n ▸ Credits: 0.13 • Time: 5s\n";
        var v = LlmJson.parseFields(kiro);
        assertThat(v.stringValuesByField().get("couponCode")).containsExactly("GOLD-1234", "SILV-5678");
    }

    @Test
    void throwsWhenNoFieldsObject() {  // REQ-017
        assertThatThrownBy(() -> LlmJson.parseFields("sorry, I cannot help"))
                .isInstanceOf(IllegalStateException.class);
    }
}
