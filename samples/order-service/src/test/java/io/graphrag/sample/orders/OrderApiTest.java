package io.graphrag.sample.orders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserRepository users;

    private String token;

    @BeforeEach
    void obtainToken() {
        token = AuthHelper.obtainToken(rest);
    }

    private ResponseEntity<String> post(String json) {
        return rest.postForEntity("/api/orders",
                new HttpEntity<>(json, AuthHelper.jsonWithAuth(token)), String.class);
    }

    @Test
    void createOrder_returns201WithPendingStatus() {
        users.save(new User("u-1", "John"));

        ResponseEntity<String> response =
                post("{\"userId\":\"u-1\",\"amount\":100,\"type\":\"NORMAL\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).contains("\"status\":\"PENDING\"").contains("\"id\":");
    }

    @Test
    void unknownUser_returns404() {
        ResponseEntity<String> response =
                post("{\"userId\":\"nobody\",\"amount\":100,\"type\":\"EXPRESS\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void invalidAmount_returns400() {
        users.save(new User("u-2", "Jane"));
        ResponseEntity<String> response =
                post("{\"userId\":\"u-2\",\"amount\":0,\"type\":\"EXPRESS\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
