package io.graphrag.generator.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileGraphRagClientTest {

    private final FileGraphRagClient client =
            new FileGraphRagClient(Path.of("src/test/resources/fixture-graph"));

    @Test
    void endpoint_path_sql_tables_accessible() {
        assertThat(client.endpoint("post-api-orders").path()).isEqualTo("/api/orders");
        assertThat(client.path("post-api-orders-happy").expectedStatus()).isEqualTo(201);
        assertThat(client.sqlForPath("post-api-orders-happy")).hasSize(2);
        assertThat(client.tables()).hasSize(2);
    }

    @Test
    void unknownId_failsFast() {
        assertThatThrownBy(() -> client.endpoint("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }
}
