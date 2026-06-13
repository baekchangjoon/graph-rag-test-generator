package io.graphrag.sample.orders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderReadApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserRepository users;

    private String token;

    @BeforeEach
    void setup() {
        token = AuthHelper.obtainToken(rest);
    }

    private long createOrder(String userId) {
        users.save(new User(userId, "Test User " + userId));
        String body = String.format(
                "{\"userId\":\"%s\",\"amount\":100,\"type\":\"NORMAL\"}", userId);
        ResponseEntity<String> resp = rest.postForEntity(
                "/api/orders",
                new HttpEntity<>(body, AuthHelper.jsonWithAuth(token)),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        // extract id from response: {"id":N,"status":"PENDING"}
        String rb = resp.getBody();
        int idStart = rb.indexOf("\"id\":") + 5;
        int idEnd = rb.indexOf(',', idStart);
        if (idEnd == -1) idEnd = rb.indexOf('}', idStart);
        return Long.parseLong(rb.substring(idStart, idEnd).trim());
    }

    @Test
    void getById_returnsSeededOrder() {
        long createdId = createOrder("r-u1");

        ResponseEntity<String> resp = rest.exchange(
                "/api/orders/" + createdId,
                HttpMethod.GET,
                new HttpEntity<>(AuthHelper.jsonWithAuth(token)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"id\":" + createdId);
    }

    @Test
    void getById_missing_returns404() {
        ResponseEntity<String> resp = rest.exchange(
                "/api/orders/999999",
                HttpMethod.GET,
                new HttpEntity<>(AuthHelper.jsonWithAuth(token)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void getByUserId_returnsList() {
        String userId = "r-u2";
        createOrder(userId);

        ResponseEntity<String> resp = rest.exchange(
                "/api/orders?userId=" + userId,
                HttpMethod.GET,
                new HttpEntity<>(AuthHelper.jsonWithAuth(token)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"id\":");
    }

    @Test
    void getById_withoutToken_returns403() {
        ResponseEntity<String> resp = rest.getForEntity("/api/orders/1", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }
}
