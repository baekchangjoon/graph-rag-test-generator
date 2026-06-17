package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SutProcessTest {
    @Test void springApplicationJson_includesBatchSizeZeroWhenRequested() {
        String json = io.graphrag.builder.env.SutProcess.springApplicationJson(java.util.Map.of(), true);
        assertThat(json).contains("\"spring.jpa.properties.hibernate.jdbc.batch_size\":\"0\"");
        assertThat(json).contains("org.hibernate.SQL");
    }
}
