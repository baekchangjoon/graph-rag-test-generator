package io.graphrag.builder.schema;

import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaExtractorPostgresTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    @Test
    void extractsTablesFromPostgresPublicSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGSERIAL PRIMARY KEY, user_id VARCHAR(64) NOT NULL)");
            List<TableSchema> tables = new SchemaExtractor().extract(c);
            assertThat(tables).extracting(TableSchema::name).contains("orders");
        }
    }
}
