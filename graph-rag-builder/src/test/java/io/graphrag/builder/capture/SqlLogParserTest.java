package io.graphrag.builder.capture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLogParserTest {

    private static final String HIBERNATE_LOG = """
            2026-06-10T08:49:19.100+09:00 DEBUG 1 --- [nio-8080-exec-1] org.hibernate.SQL : select u1_0.id,u1_0.name from users u1_0 where u1_0.id=?
            2026-06-10T08:49:19.101+09:00 TRACE 1 --- [nio-8080-exec-1] org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [probe-userId]
            """;

    private static final String MYBATIS_LOG = """
            2026-06-10T09:00:00.100+09:00 DEBUG 1 --- [nio-8080-exec-2] i.g.s.o.OrderSearchMapper.search : ==>  Preparing: select id, user_id, amount, type, status from orders WHERE user_id = ? and amount >= ? order by id
            2026-06-10T09:00:00.101+09:00 TRACE 1 --- [nio-8080-exec-2] i.g.s.o.OrderSearchMapper.search : ==> Parameters: probe-userId(String), 60(Integer)
            2026-06-10T09:00:00.102+09:00 TRACE 1 --- [nio-8080-exec-2] i.g.s.o.OrderSearchMapper.search : <==      Total: 1
            """;

    @Test
    void parsesHibernateFormat() {
        List<ParsedSql> parsed = SqlLogParser.parse(HIBERNATE_LOG);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).kind()).isEqualTo("SELECT");
        assertThat(parsed.get(0).tableName()).isEqualTo("users");
        assertThat(parsed.get(0).bindings())
                .containsExactly(new ParsedSql.Binding(1, "probe-userId"));
    }

    @Test
    void parsesMybatisFormat() {
        List<ParsedSql> parsed = SqlLogParser.parse(MYBATIS_LOG);

        assertThat(parsed).hasSize(1);
        ParsedSql sql = parsed.get(0);
        assertThat(sql.kind()).isEqualTo("SELECT");
        assertThat(sql.tableName()).isEqualTo("orders");
        assertThat(sql.bindings()).containsExactly(
                new ParsedSql.Binding(1, "probe-userId"),
                new ParsedSql.Binding(2, "60"));
        assertThat(sql.columnForPosition(1)).isEqualTo("user_id");
    }

    @Test
    void mybatisNullParameter_isCaptured() {
        String log = """
                x DEBUG 1 --- [t] M.search : ==>  Preparing: select * from orders where type = ?
                x TRACE 1 --- [t] M.search : ==> Parameters: null
                """;
        List<ParsedSql> parsed = SqlLogParser.parse(log);
        assertThat(parsed.get(0).bindings())
                .containsExactly(new ParsedSql.Binding(1, "null"));
    }

    @Test
    void mixedLog_preservesStatementOrder() {
        List<ParsedSql> parsed = SqlLogParser.parse(HIBERNATE_LOG + MYBATIS_LOG);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).tableName()).isEqualTo("users");
        assertThat(parsed.get(1).tableName()).isEqualTo("orders");
    }

    @Test
    void joinAlias_resolvesBindingTable() {
        String log = """
                x DEBUG 1 --- [t] org.hibernate.SQL : select count(o1_0.id) from orders o1_0 left join users u1_0 on u1_0.id=o1_0.user_id where u1_0.id=?
                x TRACE 1 --- [t] org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [probe-userId]
                """;
        ParsedSql parsed = SqlLogParser.parse(log).get(0);
        assertThat(parsed.tableName()).isEqualTo("orders");
        assertThat(parsed.columnForPosition(1)).isEqualTo("id");
        // 바인딩이 실제로 속한 테이블은 별칭 u1_0 → users
        assertThat(parsed.bindingTableForPosition(1)).isEqualTo("users");
    }

    @Test
    void emptyLog_returnsNothing() {
        assertThat(SqlLogParser.parse("")).isEmpty();
    }
}
