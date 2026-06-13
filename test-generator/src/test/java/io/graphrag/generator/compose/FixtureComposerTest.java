package io.graphrag.generator.compose;

import io.graphrag.generator.client.FileGraphRagClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureComposerTest {

    private final FileGraphRagClient client =
            new FileGraphRagClient(Path.of("src/test/resources/fixture-graph"));

    private ComposedFixture compose() {
        return new FixtureComposer().compose(
                client.path("post-api-orders-happy"),
                client.sqlForPath("post-api-orders-happy"),
                client.tables());
    }

    @Test
    void apiParamLinkedToKeyColumn_becomesTestIdVariable() {
        ComposedFixture fixture = compose();

        assertThat(fixture.vars()).hasSize(1);
        assertThat(fixture.vars().get(0).name()).isEqualTo("userId");
        assertThat(fixture.vars().get(0).valueExpr()).isEqualTo("scope.testId() + \"-user\"");
    }

    @Test
    void fixtureInsert_targetsSelectedParentTableWithNotNullFill() {
        ComposedFixture fixture = compose();

        assertThat(fixture.inserts()).hasSize(1);
        ComposedFixture.Stmt insert = fixture.inserts().get(0);
        assertThat(insert.sql()).isEqualTo("INSERT INTO users (id, name) VALUES (?, ?)");
        assertThat(insert.argExprs()).containsExactly("userId", "\"probe\"");
    }

    @Test
    void cleanup_isChildFirstReverseFkOrder() {
        ComposedFixture fixture = compose();

        assertThat(fixture.deletes()).extracting(ComposedFixture.Stmt::sql).containsExactly(
                "DELETE FROM orders WHERE user_id = ?",
                "DELETE FROM users WHERE id = ?");
    }

    @Test
    void body_substitutesOnlyKeyLinkedFields() {
        ComposedFixture fixture = compose();

        assertThat(fixture.bodyFormat())
                .isEqualTo("{\"userId\":\"%s\",\"amount\":1,\"type\":\"sample-type\"}");
        assertThat(fixture.bodyArgExprs()).containsExactly("userId");
    }

    @Test
    void notFoundPath_substitutesVarButSkipsFixture() {
        // 404 = 사전 데이터가 "없어야" 재현되는 path → INSERT 합성 금지, 치환은 유지
        ComposedFixture fixture = new FixtureComposer().compose(
                client.path("post-api-orders-s404-1"),
                client.sqlForPath("post-api-orders-s404-1"),
                client.tables());

        assertThat(fixture.vars()).extracting(ComposedFixture.Var::name).containsExactly("userId");
        assertThat(fixture.inserts()).isEmpty();
        assertThat(fixture.deletes()).isEmpty();
        assertThat(fixture.bodyArgExprs()).containsExactly("userId");
    }

    @Test
    void conflictPathWithHttpCall_stillSeedsFixture() throws Exception {
        // 409: 4xx지만 "행 존재"가 전제 — SELECT 이후 외부 HTTP 호출이 증거
        var path = new io.graphrag.model.ExploredPath(
                "p-409", "post-api-orders",
                io.graphrag.model.Json.mapper().readTree(
                        "{\"userId\":\"probe-userId\",\"amount\":1000000,\"type\":\"EXPRESS\"}"),
                409, io.graphrag.model.Json.mapper().nullNode(),
                List.of("sql-p-409-1"), List.of("http-p-409-1"),
                List.of(), "fuzzer", List.of(), List.of(), List.of());
        var select = new io.graphrag.model.CapturedSql(
                "sql-p-409-1", "p-409", "SELECT",
                "select u1_0.id,u1_0.name from users u1_0 where u1_0.id=?", "users",
                List.of(new io.graphrag.model.SqlBinding(1, "id", "probe-userId",
                        io.graphrag.model.BindingOrigin.API_PARAM)));

        ComposedFixture fixture = new FixtureComposer()
                .compose(path, List.of(select), client.tables());

        assertThat(fixture.inserts()).extracting(ComposedFixture.Stmt::sql)
                .containsExactly("INSERT INTO users (id, name) VALUES (?, ?)");
    }

    @Test
    void searchPath_seedsChildWithFkParentAndSkipsSerialPk() {
        ComposedFixture fixture = new FixtureComposer().compose(
                client.path("post-api-orders-search-s200-1"),
                client.sqlForPath("post-api-orders-search-s200-1"),
                client.tables());

        // 부모(users) 먼저, 자식(orders)은 BIGSERIAL PK 제외 + NOT NULL 채움
        assertThat(fixture.inserts()).extracting(ComposedFixture.Stmt::sql).containsExactly(
                "INSERT INTO users (id, name) VALUES (?, ?)",
                "INSERT INTO orders (user_id, amount, type, status) VALUES (?, ?, ?, ?)");
        assertThat(fixture.inserts().get(1).argExprs())
                .containsExactly("userId", "1", "\"probe\"", "\"probe\"");
        // cleanup은 자식 먼저
        assertThat(fixture.deletes()).extracting(ComposedFixture.Stmt::sql).containsExactly(
                "DELETE FROM orders WHERE user_id = ?",
                "DELETE FROM users WHERE id = ?");
    }

    @Test
    void assertions_literalEquals_othersNotNull() {
        ComposedFixture fixture = compose();

        assertThat(fixture.assertions()).containsExactly(
                new ComposedFixture.Assertion("id", "notNullValue()"),
                new ComposedFixture.Assertion("status", "equalTo(\"PENDING\")"));
    }

    @Test
    void readPathSeedValues_withSpecialChars_areEscapedAsJavaStringLiterals() throws Exception {
        var path = new io.graphrag.model.ExploredPath(
                "p-read", "get-api-items",
                io.graphrag.model.Json.mapper().readTree("{}"),
                200, io.graphrag.model.Json.mapper().readTree("{}"),
                List.of(), List.of(),
                List.of(), "fuzzer", List.of(), List.of(), List.of());
        var seed = new io.graphrag.model.RequiredSeed(
                "s1", "p-read", "items",
                List.of("id", "name"),
                List.of("ab\"cd", "x\\y"));

        ComposedFixture fixture = new FixtureComposer()
                .compose(path, List.of(), List.of(), List.of(seed));

        assertThat(fixture.inserts()).hasSize(1);
        assertThat(fixture.inserts().get(0).argExprs())
                .containsExactly("\"ab\\\"cd\"", "\"x\\\\y\"");
        assertThat(fixture.deletes()).hasSize(1);
        assertThat(fixture.deletes().get(0).argExprs())
                .containsExactly("\"ab\\\"cd\"");
    }
}
