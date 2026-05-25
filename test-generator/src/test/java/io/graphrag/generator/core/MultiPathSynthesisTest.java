package io.graphrag.generator.core;

import io.graphrag.model.Binding;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import io.graphrag.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiPathSynthesisTest {

    private final Endpoint orderPost = new Endpoint(
            "POST:/api/orders", HttpMethod.POST, "/api/orders",
            "demo-sut", "OrdersController", "create", false, List.of());

    private ExploredPath path(String id, int exitStatus, Map<String, Object> body) {
        return new ExploredPath(
                id, orderPost.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), body),
                null, List.of(), exitStatus, null, "cov-" + id, "abc1234");
    }

    private CapturedSql insertSql(String pathId, String table, Object... values) {
        List<Binding> bindings = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            bindings.add(new Binding(i, values[i], BindingOrigin.COMPUTED, null));
        }
        String cols = String.join(", ", java.util.Collections.nCopies(values.length, "?"));
        String sql = "INSERT INTO " + table + " VALUES (" + cols + ")";
        return new CapturedSql(
                "sql-" + pathId + "-" + table, pathId, CapturedSqlType.INSERT, sql, bindings,
                CapturedSqlSource.JPA_REPOSITORY_DERIVED,
                new SourceLocation("X", "y", 1),
                List.of(table), List.of());
    }

    @Test
    void multiPathProducesOneClassWithMultipleTestMethods() {
        MultiPathSynthesisInput input = new MultiPathSynthesisInput(
                orderPost,
                List.of(
                        new PathContext(
                                path("p1", 201, Map.of("amount", 100, "type", "EXPRESS")),
                                List.of(insertSql("p1", "orders", "o-1", "u-1", 100))),
                        new PathContext(
                                path("p2", 400, Map.of("amount", 0)),
                                List.of()),
                        new PathContext(
                                path("p3", 404, Map.of("userId", "missing")),
                                List.of())),
                "com.example.tests");

        String java = TestSynthesizer.synthesizeMulti(input);

        // 한 클래스
        assertThat(java).containsOnlyOnce("class OrdersPostTest");

        // 세 개 @Test (path id가 메소드 이름에 들어감)
        long testCount = java.lines().filter(l -> l.contains("@Test")).count();
        assertThat(testCount).isEqualTo(3);

        // 각 path의 exitStatus를 statusCode로 단언
        assertThat(java).contains(".statusCode(201)");
        assertThat(java).contains(".statusCode(400)");
        assertThat(java).contains(".statusCode(404)");
    }

    @Test
    void testMethodNamesAreUniqueAndIncludePathId() {
        MultiPathSynthesisInput input = new MultiPathSynthesisInput(
                orderPost,
                List.of(
                        new PathContext(path("happy", 201, Map.of()), List.of()),
                        new PathContext(path("badAmount", 400, Map.of()), List.of()),
                        new PathContext(path("noUser", 404, Map.of()), List.of())),
                "com.example.tests");

        String java = TestSynthesizer.synthesizeMulti(input);

        assertThat(java).contains("void path_happy(");
        assertThat(java).contains("void path_badAmount(");
        assertThat(java).contains("void path_noUser(");
    }

    @Test
    void capturedSqlAppearsAsFixtureInOwningTestOnly() {
        MultiPathSynthesisInput input = new MultiPathSynthesisInput(
                orderPost,
                List.of(
                        new PathContext(
                                path("p1", 201, Map.of()),
                                List.of(insertSql("p1", "users", "u-1", "John"))),
                        new PathContext(
                                path("p2", 201, Map.of()),
                                List.of(insertSql("p2", "products", "pr-1", "Item")))),
                "com.example.tests");

        String java = TestSynthesizer.synthesizeMulti(input);

        assertThat(java).contains("INSERT INTO users");
        assertThat(java).contains("INSERT INTO products");
        // 각 INSERT는 자기 path의 메소드 안에 위치 (정확한 location은 아래 통합 어설션)
        int usersIdx = java.indexOf("INSERT INTO users");
        int productsIdx = java.indexOf("INSERT INTO products");
        int p1MethodIdx = java.indexOf("void path_p1(");
        int p2MethodIdx = java.indexOf("void path_p2(");
        assertThat(usersIdx).isGreaterThan(p1MethodIdx);
        assertThat(productsIdx).isGreaterThan(p2MethodIdx);
    }

    @Test
    void multiPathDeterministicOutput() {
        MultiPathSynthesisInput input = new MultiPathSynthesisInput(
                orderPost,
                List.of(
                        new PathContext(path("a", 201, Map.of()), List.of()),
                        new PathContext(path("b", 400, Map.of()), List.of())),
                "com.example.tests");

        assertThat(TestSynthesizer.synthesizeMulti(input))
                .isEqualTo(TestSynthesizer.synthesizeMulti(input));
    }

    @Test
    void requestBodyIsSerializedFromSampleInputBody() {
        MultiPathSynthesisInput input = new MultiPathSynthesisInput(
                orderPost,
                List.of(new PathContext(
                        path("p1", 201, Map.of("userId", "u-1", "amount", 100, "type", "EXPRESS")),
                        List.of())),
                "com.example.tests");

        String java = TestSynthesizer.synthesizeMulti(input);

        // 입력 body의 필드들이 생성된 요청 body 문자열에 포함
        assertThat(java).contains("\\\"userId\\\":\\\"u-1\\\"")
                .contains("\\\"amount\\\":100");
    }
}
