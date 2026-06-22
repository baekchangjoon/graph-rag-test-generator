package io.graphrag.generator.compose;

import io.graphrag.generator.client.FileGraphRagClient;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Outcome;
import io.graphrag.model.SqlBinding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-005: lookupSucceeded는 path.outcome()==SUCCESS 기준으로 판단해야 한다.
 * 200-wrapped error envelope(outcome=FAILURE, expectedStatus=200)에서
 * spurious seed INSERT가 생성되어서는 안 된다.
 */
class LookupSucceededOutcomeTest {

    private final FileGraphRagClient client =
            new FileGraphRagClient(Path.of("src/test/resources/fixture-graph"));

    /**
     * envelope path: HTTP 200이지만 outcome=FAILURE(에러 엔벨로프).
     * SELECT가 마지막 SQL이고 외부 HTTP 호출도 없으므로 fallback이 outcome을 참조한다.
     * → INSERT가 생성되어서는 안 된다.
     */
    @Test
    void envelopedStatus200WithOutcomeFailure_noSeedInsert() throws Exception {
        ExploredPath envelopePath = new ExploredPath(
                "p-env-200", "post-api-orders",
                io.graphrag.model.Json.mapper().readTree(
                        "{\"userId\":\"probe-userId\",\"amount\":1,\"type\":\"EXPRESS\"}"),
                200,                        // expectedStatus=200 (HTTP 레벨은 성공)
                io.graphrag.model.Json.mapper().nullNode(),
                List.of("sql-env-1"),
                List.of(),                  // 외부 HTTP 호출 없음
                List.of(), "fuzzer", List.of(), List.of(), List.of(),
                List.of(), java.util.Map.of(),
                Outcome.Kind.FAILURE,       // outcome=FAILURE (에러 엔벨로프)
                200, "200"
        );

        // SELECT만 있고 이것이 마지막 SQL → fallback이 발동 → outcome 기준
        CapturedSql select = new CapturedSql(
                "sql-env-1", "p-env-200", "SELECT",
                "select u1_0.id,u1_0.name from users u1_0 where u1_0.id=?", "users",
                List.of(new SqlBinding(1, "id", "probe-userId", BindingOrigin.API_PARAM)));

        ComposedFixture fixture = new FixtureComposer()
                .compose(envelopePath, List.of(select), client.tables());

        assertThat(fixture.inserts())
                .as("outcome=FAILURE인 에러 엔벨로프 path에서는 seed INSERT가 없어야 한다")
                .isEmpty();
    }

    /**
     * 회귀: 일반 200 path(outcome=SUCCESS)는 기존대로 seed INSERT가 생성되어야 한다.
     */
    @Test
    void normalStatus200WithOutcomeSuccess_seedInsertPresent() throws Exception {
        ExploredPath successPath = new ExploredPath(
                "p-suc-200", "post-api-orders",
                io.graphrag.model.Json.mapper().readTree(
                        "{\"userId\":\"probe-userId\",\"amount\":1,\"type\":\"EXPRESS\"}"),
                200,
                io.graphrag.model.Json.mapper().nullNode(),
                List.of("sql-suc-1"),
                List.of(),
                List.of(), "fuzzer", List.of(), List.of(), List.of(),
                List.of(), java.util.Map.of(),
                Outcome.Kind.SUCCESS,       // outcome=SUCCESS (정상 응답)
                200, "200"
        );

        CapturedSql select = new CapturedSql(
                "sql-suc-1", "p-suc-200", "SELECT",
                "select u1_0.id,u1_0.name from users u1_0 where u1_0.id=?", "users",
                List.of(new SqlBinding(1, "id", "probe-userId", BindingOrigin.API_PARAM)));

        ComposedFixture fixture = new FixtureComposer()
                .compose(successPath, List.of(select), client.tables());

        assertThat(fixture.inserts())
                .as("outcome=SUCCESS인 일반 200 path에서는 seed INSERT가 있어야 한다")
                .isNotEmpty();
    }
}
