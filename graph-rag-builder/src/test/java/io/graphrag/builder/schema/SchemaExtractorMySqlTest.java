package io.graphrag.builder.schema;

import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaExtractorMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orderdb");

    @Test
    void extractsTablesColumnsAndPkFromMySql() throws Exception {
        try (Connection c = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                    + "user_id VARCHAR(64) NOT NULL, amount INT NULL)");

            List<TableSchema> tables = new SchemaExtractor().extract(c);

            // containsExactlyInAnyOrder: catalog 스코핑이 깨져 다른 DB/시스템 테이블이 새면 실패 → 진짜 가드
            // (catalog=getCatalog()=orderdb, schema=null 경로가 실제로 동작함을 보증).
            assertThat(tables).extracting(TableSchema::name).containsExactlyInAnyOrder("orders");
            TableSchema orders = tables.stream()
                    .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
            assertThat(orders.columns()).extracting("name")
                    .contains("id", "user_id", "amount");
            assertThat(orders.columns().stream()
                    .filter(col -> col.name().equals("id")).findFirst().orElseThrow().primaryKey())
                    .isTrue();
        }
    }
}
