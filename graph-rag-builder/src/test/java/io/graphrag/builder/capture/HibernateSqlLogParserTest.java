package io.graphrag.builder.capture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HibernateSqlLogParserTest {

    private static final String LOG = """
            2026-06-10T08:49:18.000+09:00  INFO 1 --- [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet : init
            2026-06-10T08:49:19.100+09:00 DEBUG 1 --- [nio-8080-exec-1] org.hibernate.SQL : select u1_0.id,u1_0.name from users u1_0 where u1_0.id=?
            2026-06-10T08:49:19.101+09:00 TRACE 1 --- [nio-8080-exec-1] org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [probe-userId]
            2026-06-10T08:49:19.200+09:00 DEBUG 1 --- [nio-8080-exec-1] org.hibernate.SQL : insert into orders (amount,status,type,user_id) values (?,?,?,?)
            2026-06-10T08:49:19.201+09:00 TRACE 1 --- [nio-8080-exec-1] org.hibernate.orm.jdbc.bind : binding parameter (1:INTEGER) <- [1]
            2026-06-10T08:49:19.202+09:00 TRACE 1 --- [nio-8080-exec-1] org.hibernate.orm.jdbc.bind : binding parameter (2:VARCHAR) <- [PENDING]
            2026-06-10T08:49:19.203+09:00 TRACE 1 --- [nio-8080-exec-1] org.hibernate.orm.jdbc.bind : binding parameter (3:VARCHAR) <- [sample-type]
            2026-06-10T08:49:19.204+09:00 TRACE 1 --- [nio-8080-exec-1] org.hibernate.orm.jdbc.bind : binding parameter (4:VARCHAR) <- [probe-userId]
            """;

    @Test
    void parse_extractsStatementsWithBindings() {
        List<ParsedSql> parsed = HibernateSqlLogParser.parse(LOG);

        assertThat(parsed).hasSize(2);

        ParsedSql select = parsed.get(0);
        assertThat(select.sql()).startsWith("select u1_0.id");
        assertThat(select.kind()).isEqualTo("SELECT");
        assertThat(select.tableName()).isEqualTo("users");
        assertThat(select.bindings()).containsExactly(new ParsedSql.Binding(1, "probe-userId"));

        ParsedSql insert = parsed.get(1);
        assertThat(insert.kind()).isEqualTo("INSERT");
        assertThat(insert.tableName()).isEqualTo("orders");
        assertThat(insert.bindings()).hasSize(4);
        assertThat(insert.columnForPosition(1)).isEqualTo("amount");
        assertThat(insert.columnForPosition(4)).isEqualTo("user_id");
    }

    @Test
    void parse_selectWhereColumn_isBestEffort() {
        List<ParsedSql> parsed = HibernateSqlLogParser.parse(LOG);
        // select의 where u1_0.id=? → 컬럼 "id" (별칭 제거)
        assertThat(parsed.get(0).columnForPosition(1)).isEqualTo("id");
    }

    @Test
    void parse_emptyLog_returnsNothing() {
        assertThat(HibernateSqlLogParser.parse("")).isEmpty();
    }
}
