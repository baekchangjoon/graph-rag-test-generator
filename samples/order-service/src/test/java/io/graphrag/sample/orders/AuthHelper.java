package io.graphrag.sample.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 테스트에서 admin 토큰을 가져오는 헬퍼.
 */
class AuthHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String obtainToken(TestRestTemplate rest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "/api/auth/login",
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"password\"}", headers),
                String.class);
        String body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("login response body is null, status=" + response.getStatusCode());
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode tokenNode = node.get("token");
            if (tokenNode == null || tokenNode.isNull()) {
                throw new IllegalStateException("No 'token' field in login response: " + body);
            }
            return tokenNode.asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse login response: " + body, e);
        }
    }

    static HttpHeaders jsonWithAuth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + token);
        return h;
    }
}
