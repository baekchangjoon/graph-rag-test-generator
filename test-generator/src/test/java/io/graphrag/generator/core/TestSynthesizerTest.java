package io.graphrag.generator.core;

import io.graphrag.model.Binding;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSynthesizerTest {

    private final Endpoint orderPost = new Endpoint(
            "POST:/api/orders", HttpMethod.POST, "/api/orders",
            "demo-sut", "OrdersController", "createOrder", false, List.of());

    private final CapturedSql insertUser = new CapturedSql(
            "sql-1", "path-1", CapturedSqlType.INSERT,
            "INSERT INTO users(id, name) VALUES (?, ?)",
            List.of(
                    new Binding(0, "u-1", BindingOrigin.COMPUTED, null),
                    new Binding(1, "John", BindingOrigin.COMPUTED, null)),
            CapturedSqlSource.JPA_REPOSITORY_DERIVED,
            new SourceLocation("X", "y", 1),
            List.of("users"), List.of("id", "name"));

    private final CapturedSql insertOrder = new CapturedSql(
            "sql-2", "path-1", CapturedSqlType.INSERT,
            "INSERT INTO orders(id, user_id, status) VALUES (?, ?, ?)",
            List.of(
                    new Binding(0, "o-1", BindingOrigin.COMPUTED, null),
                    new Binding(1, "u-1", BindingOrigin.COMPUTED, null),
                    new Binding(2, "PENDING", BindingOrigin.COMPUTED, null)),
            CapturedSqlSource.JPA_REPOSITORY_DERIVED,
            new SourceLocation("X", "y", 2),
            List.of("orders"), List.of("id", "user_id", "status"));

    @Test
    void outputContainsClassDeclarationDerivedFromEndpoint() {
        String java = TestSynthesizer.synthesize(
                new SynthesisInput(orderPost, List.of(insertUser), "com.example.tests"));

        // POST /api/orders → "OrdersPostTest"
        assertThat(java).contains("class OrdersPostTest");
        assertThat(java).contains("package com.example.tests;");
    }

    @Test
    void outputContainsStandardImports() {
        String java = TestSynthesizer.synthesize(
                new SynthesisInput(orderPost, List.of(insertUser), "com.example.tests"));

        assertThat(java)
                .contains("import io.restassured.RestAssured;")
                .contains("import io.restassured.http.ContentType;")
                .contains("import org.junit.jupiter.api.BeforeAll;")
                .contains("import org.junit.jupiter.api.BeforeEach;")
                .contains("import org.junit.jupiter.api.AfterEach;")
                .contains("import org.junit.jupiter.api.Test;")
                .contains("import static io.restassured.RestAssured.given;");
    }

    @Test
    void outputContainsTestIdGenerationInBeforeEach() {
        String java = TestSynthesizer.synthesize(
                new SynthesisInput(orderPost, List.of(insertUser), "com.example.tests"));

        assertThat(java)
                .contains("UUID.randomUUID()")
                .contains("@BeforeEach");
    }

    @Test
    void outputContainsFixtureInsertsFromCapturedSql() {
        String java = TestSynthesizer.synthesize(
                new SynthesisInput(orderPost, List.of(insertUser, insertOrder), "com.example.tests"));

        assertThat(java).contains("INSERT INTO users");
        assertThat(java).contains("INSERT INTO orders");
    }

    @Test
    void outputContainsCleanupDeletesInReverseOrder() {
        String java = TestSynthesizer.synthesize(
                new SynthesisInput(orderPost, List.of(insertUser, insertOrder), "com.example.tests"));

        int deleteOrdersIdx = java.indexOf("DELETE FROM orders");
        int deleteUsersIdx = java.indexOf("DELETE FROM users");
        assertThat(deleteOrdersIdx).isGreaterThanOrEqualTo(0);
        assertThat(deleteUsersIdx).isGreaterThan(deleteOrdersIdx);   // orders 먼저, users 나중
    }

    @Test
    void outputContainsRestAssuredInvocation() {
        String java = TestSynthesizer.synthesize(
                new SynthesisInput(orderPost, List.of(), "com.example.tests"));

        assertThat(java)
                .contains(".post(\"/api/orders\")")
                .contains("given()")
                .contains(".then()")
                .contains(".statusCode(");
    }

    @Test
    void differentEndpointsProduceDifferentClassNames() {
        Endpoint getUsers = new Endpoint(
                "GET:/api/users", HttpMethod.GET, "/api/users",
                "demo-sut", "UsersController", "list", false, List.of());

        String javaPost = TestSynthesizer.synthesize(
                new SynthesisInput(orderPost, List.of(), "com.example.tests"));
        String javaGet = TestSynthesizer.synthesize(
                new SynthesisInput(getUsers, List.of(), "com.example.tests"));

        assertThat(javaPost).contains("OrdersPost");
        assertThat(javaGet).contains("UsersGet");
    }

    @Test
    void deterministicWithSameInput() {
        SynthesisInput input = new SynthesisInput(orderPost, List.of(insertUser), "com.example.tests");
        String a = TestSynthesizer.synthesize(input);
        String b = TestSynthesizer.synthesize(input);
        assertThat(a).isEqualTo(b);
    }
}
