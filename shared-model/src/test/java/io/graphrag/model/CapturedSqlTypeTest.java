package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedSqlTypeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void containsAllExpectedValues() {
        assertThat(CapturedSqlType.values())
                .containsExactly(
                        CapturedSqlType.SELECT,
                        CapturedSqlType.INSERT,
                        CapturedSqlType.UPDATE,
                        CapturedSqlType.DELETE,
                        CapturedSqlType.DDL);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        for (CapturedSqlType v : CapturedSqlType.values()) {
            String json = mapper.writeValueAsString(v);
            assertThat(mapper.readValue(json, CapturedSqlType.class)).isEqualTo(v);
        }
    }
}
