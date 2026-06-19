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

    @Test
    void parsesHibernate5AbbreviatedBasicBinder() {
        String log = """
                2026-06-18T10:00:00.100+09:00 DEBUG 1 --- [tram-c-1] org.hibernate.SQL : insert into order_events (type,user_id,id) values (?,?,?)
                2026-06-18T10:00:00.101+09:00 TRACE 1 --- [tram-c-1] o.h.type.descriptor.sql.BasicBinder : binding parameter [1] as [VARCHAR] - [CREATED]
                2026-06-18T10:00:00.102+09:00 TRACE 1 --- [tram-c-1] o.h.type.descriptor.sql.BasicBinder : binding parameter [2] as [VARCHAR] - [user-1]
                2026-06-18T10:00:00.103+09:00 TRACE 1 --- [tram-c-1] o.h.type.descriptor.sql.BasicBinder : binding parameter [3] as [VARCHAR] - [evt-1]
                """;
        List<ParsedSql> parsed = SqlLogParser.parse(log);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).bindings()).containsExactly(
                new ParsedSql.Binding(1, "CREATED"),
                new ParsedSql.Binding(2, "user-1"),
                new ParsedSql.Binding(3, "evt-1"));
    }

    @Test
    void parsesHibernate5FullLoggerNameAndNull() {
        String log = """
                x DEBUG 1 --- [t] org.hibernate.SQL : update orders set status=? where id=?
                x TRACE 1 --- [t] org.hibernate.type.descriptor.sql.BasicBinder : binding parameter [1] as [VARCHAR] - [null]
                x TRACE 1 --- [t] org.hibernate.type.descriptor.sql.BasicBinder : binding parameter [2] as [BIGINT] - [42]
                """;
        List<ParsedSql> parsed = SqlLogParser.parse(log);
        assertThat(parsed.get(0).bindings()).containsExactly(
                new ParsedSql.Binding(1, "null"),
                new ParsedSql.Binding(2, "42"));
    }

    @Test
    void extractTraceId_fromTwoFieldSleuthBracket() {
        // Boot 2.7 + Sleuth 3.x 실제 출력: [32hexTraceId,spanId] (app 이름 없음)
        String line = "2026-06-18T10:00:00.100+09:00 DEBUG 1 --- "
                + "[aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbb] "
                + "[tram-c-1] org.hibernate.SQL : select 1";
        assertThat(SqlLogParser.extractTraceId(line))
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void extractTraceId_twoFieldBracket_mixedCase_lowercasedOnReturn() {
        // 대소문자 혼용 → 소문자로 정규화
        String line = "x DEBUG 1 --- [AABBCCDDEEFF00112233445566778899,AABBCCDD11223344] "
                + "org.hibernate.SQL : select 1";
        assertThat(SqlLogParser.extractTraceId(line))
                .isEqualTo("aabbccddeeff00112233445566778899");
    }

    @Test
    void extractTraceId_fromSleuthBracket() {
        String line = "x DEBUG 1 --- [order-svc,1a2b3c4d5e6f70819a2b3c4d5e6f7081,9a2b3c4d5e6f7081] "
                + "[tram-c-1] org.hibernate.SQL : select 1";
        assertThat(SqlLogParser.extractTraceId(line))
                .isEqualTo("1a2b3c4d5e6f70819a2b3c4d5e6f7081");
    }

    @Test
    void extractTraceId_fromFourFieldSleuthBracket() {
        // Sleuth 1.x/2.x (Java8 레거시 기본): [app,traceId,spanId,exportable]
        String line = "x DEBUG 1 --- [order-svc,1a2b3c4d5e6f70819a2b3c4d5e6f7081,9a2b3c4d5e6f7081,true] "
                + "[tram-c-1] org.hibernate.SQL : select 1";
        assertThat(SqlLogParser.extractTraceId(line))
                .isEqualTo("1a2b3c4d5e6f70819a2b3c4d5e6f7081");
    }

    @Test
    void extractTraceId_doesNotFalseMatchLongerMdcKey() {
        // 더 긴 키(myTraceId=/parentTraceId=)의 접미가 traceId로 오탐되면 안 된다
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] myTraceId=1a2b3c4d5e6f70819a2b3c4d5e6f7081 c.Foo : msg"))
                .isNull();
    }

    @Test
    void extractTraceId_rejectsOverLengthToken() {
        // 32 초과 hex 토큰은 묵음 절단하지 말고 거부
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] traceId=1a2b3c4d5e6f70819a2b3c4d5e6f7081abcdef01 c.Foo : msg"))
                .isNull();
    }

    @Test
    void extractTraceId_fromMdcDump() {
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] traceId=1A2B3C4D5E6F70819A2B3C4D5E6F7081 c.Foo : msg"))
                .isEqualTo("1a2b3c4d5e6f70819a2b3c4d5e6f7081");
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] X-B3-TraceId=1a2b3c4d5e6f7081 c.Foo : msg"))
                .isEqualTo("1a2b3c4d5e6f7081");
    }

    @Test
    void extractTraceId_ignoresHexInSqlBodyAndBindValue() {
        // " : " 이후(SQL 본문/bind 값)의 hex는 trace로 잡히면 안 된다
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] org.hibernate.SQL : select * from t where id='deadbeefdeadbeef'"))
                .isNull();
        assertThat(SqlLogParser.extractTraceId(
                "x TRACE 1 --- [t] o.h.type.descriptor.sql.BasicBinder : binding parameter [1] as [VARCHAR] - [cafebabecafebabe]"))
                .isNull();
    }

    @Test
    void traceIdMatches_fullAndRight64BitAndCaseInsensitive() {
        String full = "1a2b3c4d5e6f70819a2b3c4d5e6f7081";
        assertThat(SqlLogParser.traceIdMatches(full, full)).isTrue();
        assertThat(SqlLogParser.traceIdMatches(full, "1A2B3C4D5E6F70819A2B3C4D5E6F7081")).isTrue();
        assertThat(SqlLogParser.traceIdMatches(full, "9a2b3c4d5e6f7081")).isTrue();   // 우측 16 hex
        assertThat(SqlLogParser.traceIdMatches(full, "0000000000000000")).isFalse();
    }
}
