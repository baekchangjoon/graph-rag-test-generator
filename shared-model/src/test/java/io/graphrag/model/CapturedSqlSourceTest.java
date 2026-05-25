package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedSqlSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void containsAllExpectedSources() {
        assertThat(CapturedSqlSource.values())
                .contains(
                        CapturedSqlSource.JPA_REPOSITORY_DERIVED,
                        CapturedSqlSource.JPA_QUERY_ANNOTATION,
                        CapturedSqlSource.JPA_CRITERIA,
                        CapturedSqlSource.JPA_ENTITYMANAGER,
                        CapturedSqlSource.MYBATIS_XML_MAPPER,
                        CapturedSqlSource.MYBATIS_ANNOTATION,
                        CapturedSqlSource.JDBC_TEMPLATE,
                        CapturedSqlSource.JDBC_RAW);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        for (CapturedSqlSource v : CapturedSqlSource.values()) {
            String json = mapper.writeValueAsString(v);
            assertThat(mapper.readValue(json, CapturedSqlSource.class)).isEqualTo(v);
        }
    }
}
