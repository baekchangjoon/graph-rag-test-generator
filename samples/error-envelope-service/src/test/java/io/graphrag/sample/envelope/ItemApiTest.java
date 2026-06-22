package io.graphrag.sample.envelope;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ItemApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    TestRestTemplate rest;

    @Test
    void validId_returns200WithItemJson() {
        ResponseEntity<String> response = rest.getForEntity("/items/1", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"id\":1").contains("\"name\":");
        assertThat(response.getBody()).doesNotContain("errorCode");
    }

    @Test
    void unknownId_returns200WithErrorEnvelope() {
        ResponseEntity<String> response = rest.getForEntity("/items/9999", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"errorCode\":\"404\"");
        assertThat(response.getBody()).contains("BizException");
    }
}
