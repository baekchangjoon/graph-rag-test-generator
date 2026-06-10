package io.graphrag.generator.compose;

import io.graphrag.generator.client.FileGraphRagClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
    void assertions_literalEquals_othersNotNull() {
        ComposedFixture fixture = compose();

        assertThat(fixture.assertions()).containsExactly(
                new ComposedFixture.Assertion("id", "notNullValue()"),
                new ComposedFixture.Assertion("status", "equalTo(\"PENDING\")"));
    }
}
