package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaTypesTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void columnConstructionAndJson() throws Exception {
        Column col = new Column("status", "varchar(20)", false, "PENDING");

        String json = mapper.writeValueAsString(col);
        Column back = mapper.readValue(json, Column.class);

        assertThat(back).isEqualTo(col);
        assertThat(json).contains("\"name\":\"status\"");
        assertThat(json).contains("\"type\":\"varchar(20)\"");
        assertThat(json).contains("\"nullable\":false");
        assertThat(json).contains("\"default\":\"PENDING\"");
    }

    @Test
    void columnAcceptsNullDefault() throws Exception {
        Column col = new Column("description", "text", true, null);

        String json = mapper.writeValueAsString(col);
        Column back = mapper.readValue(json, Column.class);

        assertThat(back).isEqualTo(col);
        assertThat(back.defaultValue()).isNull();
    }

    @Test
    void foreignKeyConstructionAndJson() throws Exception {
        ForeignKey fk = new ForeignKey(List.of("user_id"), "users", List.of("id"));

        String json = mapper.writeValueAsString(fk);
        ForeignKey back = mapper.readValue(json, ForeignKey.class);

        assertThat(back).isEqualTo(fk);
        assertThat(json).contains("\"from_columns\":[\"user_id\"]");
        assertThat(json).contains("\"to_table\":\"users\"");
        assertThat(json).contains("\"to_columns\":[\"id\"]");
    }

    @Test
    void tableWithColumnsForeignKeysAndConstraints() throws Exception {
        Table table = new Table(
                "orders",
                List.of(
                        new Column("id", "bigint", false, null),
                        new Column("user_id", "varchar(64)", false, null),
                        new Column("status", "varchar(20)", false, "PENDING")),
                List.of("id"),
                List.of(new ForeignKey(List.of("user_id"), "users", List.of("id"))),
                List.of(List.of("user_id", "status")),
                List.of("status IN ('PENDING','SHIPPED','CANCELLED')"),
                List.of("apiParam.userId"));

        String json = mapper.writeValueAsString(table);
        Table back = mapper.readValue(json, Table.class);

        assertThat(back).isEqualTo(table);
        assertThat(json).contains("\"primary_key\":[\"id\"]");
        assertThat(json).contains("\"unique_constraints\":[[\"user_id\",\"status\"]]");
        assertThat(json).contains("\"scope_reachable_via\":[\"apiParam.userId\"]");
    }

    @Test
    void tableEqualityByValue() {
        Table a = new Table("t", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        Table b = new Table("t", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(a).isEqualTo(b);
    }
}
