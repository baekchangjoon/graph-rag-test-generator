package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedSqlTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void constructsInsertWithBindings() {
        CapturedSql sql = new CapturedSql(
                "sql-1",
                "path-1",
                CapturedSqlType.INSERT,
                "INSERT INTO orders(user_id, amount, status) VALUES(?,?,?)",
                List.of(
                        new Binding(0, "u-1", BindingOrigin.API_PARAM, "apiParam.userId"),
                        new Binding(1, 100, BindingOrigin.API_PARAM, "apiParam.amount"),
                        new Binding(2, "PENDING", BindingOrigin.LITERAL, null)),
                CapturedSqlSource.JPA_REPOSITORY_DERIVED,
                new SourceLocation("io.graphrag.demo.OrderService", "place", 25),
                List.of("orders"),
                List.of("user_id", "amount", "status"));

        assertThat(sql.id()).isEqualTo("sql-1");
        assertThat(sql.type()).isEqualTo(CapturedSqlType.INSERT);
        assertThat(sql.bindings()).hasSize(3);
        assertThat(sql.affectedTables()).containsExactly("orders");
    }

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        CapturedSql original = new CapturedSql(
                "sql-42",
                "path-42",
                CapturedSqlType.SELECT,
                "SELECT * FROM users WHERE id = ?",
                List.of(new Binding(0, "u-1", BindingOrigin.API_PARAM, "apiParam.userId")),
                CapturedSqlSource.MYBATIS_XML_MAPPER,
                new SourceLocation("Mapper", "find", 10),
                List.of("users"),
                List.of("id"));

        String json = mapper.writeValueAsString(original);
        CapturedSql back = mapper.readValue(json, CapturedSql.class);

        assertThat(back.id()).isEqualTo(original.id());
        assertThat(back.pathId()).isEqualTo(original.pathId());
        assertThat(back.type()).isEqualTo(CapturedSqlType.SELECT);
        assertThat(back.source()).isEqualTo(CapturedSqlSource.MYBATIS_XML_MAPPER);
        assertThat(back.affectedTables()).containsExactly("users");
        assertThat(json).contains("\"raw_sql\":");
        assertThat(json).contains("\"path_id\":\"path-42\"");
        assertThat(json).contains("\"affected_tables\":[\"users\"]");
        assertThat(json).contains("\"affected_columns\":[\"id\"]");
    }

    @Test
    void legacyConstructorDefaultsReadResultRowsToEmpty() {
        CapturedSql sql = new CapturedSql(
                "legacy-1", "p1", CapturedSqlType.INSERT,
                "INSERT INTO t VALUES(?)",
                List.of(new Binding(0, "v", BindingOrigin.LITERAL, null)),
                CapturedSqlSource.JPA_REPOSITORY_DERIVED,
                new SourceLocation("X", "y", 1),
                List.of("t"), List.of("c"));

        assertThat(sql.readResultRows()).isEmpty();
    }

    @Test
    void selectWithReadResultRowsRoundTrip() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("first_name", "George");
        row.put("last_name", "Franklin");

        CapturedSql original = new CapturedSql(
                "sel-1", "p1", CapturedSqlType.SELECT,
                "SELECT first_name FROM owners WHERE id = ?",
                List.of(new Binding(0, 1, BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_ENTITYMANAGER,
                new SourceLocation("OwnerRepo", "findById", 1),
                List.of("owners"),
                List.of(),
                List.of(row));

        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"read_result_rows\":");
        assertThat(json).contains("\"first_name\":\"George\"");

        CapturedSql back = mapper.readValue(json, CapturedSql.class);
        assertThat(back.readResultRows()).hasSize(1);
        assertThat(back.readResultRows().get(0))
                .containsEntry("id", 1)
                .containsEntry("first_name", "George")
                .containsEntry("last_name", "Franklin");
    }

    @Test
    void deserializesLegacyJsonWithoutReadResultRowsField() throws Exception {
        String legacyJson = """
            {
              "id":"sql-legacy","path_id":"p","type":"INSERT",
              "raw_sql":"INSERT INTO t VALUES(?)",
              "bindings":[{"position":0,"value":"v","origin":"LITERAL","origin_ref":null}],
              "source":"JPA_REPOSITORY_DERIVED",
              "source_location":{"class":"X","method":"y","line":1},
              "affected_tables":["t"],"affected_columns":["c"]
            }
            """;
        CapturedSql back = mapper.readValue(legacyJson, CapturedSql.class);
        assertThat(back.readResultRows()).isEmpty();
    }
}
