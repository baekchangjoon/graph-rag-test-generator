package io.graphrag.sample.orders;

import org.junit.jupiter.api.BeforeEach;
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
class OrderSearchApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserRepository users;

    @Autowired
    OrderRepository orders;

    @BeforeEach
    void seed() {
        orders.deleteAll();
        users.deleteAll();
        User user = users.save(new User("u-search", "Searcher"));
        orders.save(new Order(user, 100, "EXPRESS", "PENDING"));
        orders.save(new Order(user, 50, "NORMAL", "PENDING"));
    }

    private ResponseEntity<String> search(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/orders/search", new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void searchByUserAndType_returnsMatchingOnly() {
        ResponseEntity<String> response =
                search("{\"userId\":\"u-search\",\"type\":\"EXPRESS\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"count\":1");
    }

    @Test
    void searchByMinAmount_appliesDynamicCondition() {
        ResponseEntity<String> response =
                search("{\"userId\":\"u-search\",\"minAmount\":60}");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"count\":1");
    }

    @Test
    void emptyFilter_returns400() {
        assertThat(search("{}").getStatusCode().value()).isEqualTo(400);
    }
}
