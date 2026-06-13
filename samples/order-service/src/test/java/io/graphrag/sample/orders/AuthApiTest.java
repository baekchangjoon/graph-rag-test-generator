package io.graphrag.sample.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    TestRestTemplate rest;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseEntity<String> login(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/auth/login", new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void login_withValidCredentials_returnsToken() {
        ResponseEntity<String> response = login("{\"username\":\"admin\",\"password\":\"password\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"token\"");
        assertThat(response.getBody()).contains("\"type\":\"Bearer\"");
    }

    @Test
    void login_withWrongPassword_returns400or401() {
        ResponseEntity<String> response = login("{\"username\":\"admin\",\"password\":\"wrong\"}");

        assertThat(response.getStatusCode().value()).isIn(400, 401);
    }

    @Test
    void protectedEndpoint_withoutToken_returns401or403() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/orders",
                new HttpEntity<>("{\"userId\":\"x\",\"amount\":1,\"type\":\"NORMAL\"}",
                        headersJson()),
                String.class);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void protectedEndpoint_withToken_reachesHandler() {
        ResponseEntity<String> loginResponse = login("{\"username\":\"admin\",\"password\":\"password\"}");
        assertThat(loginResponse.getStatusCode().value()).isEqualTo(200);

        String token = extractToken(loginResponse);
        assertThat(token).isNotBlank();

        HttpHeaders headers = headersJson();
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<String> response = rest.postForEntity(
                "/api/orders",
                new HttpEntity<>("{\"userId\":\"x\",\"amount\":1,\"type\":\"NORMAL\"}", headers),
                String.class);

        // 인증은 통과해야 하므로 401/403이 아닌 다른 응답 (400, 404 등)이어야 함
        assertThat(response.getStatusCode().value())
                .as("expected auth to succeed but got %s with token=[%s]",
                        response.getStatusCode().value(), token)
                .isNotIn(401, 403);
    }

    private HttpHeaders headersJson() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String extractToken(ResponseEntity<String> response) {
        String body = response.getBody();
        assertThat(body).isNotNull();
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode tokenNode = node.get("token");
            assertThat(tokenNode).isNotNull();
            return tokenNode.asText();
        } catch (Exception e) {
            throw new AssertionError("Failed to extract token from: " + body, e);
        }
    }
}
