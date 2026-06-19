package io.graphrag.sample.orders;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("docker")
class OrderExpressApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    static HttpServer inventoryStub;

    @BeforeAll
    static void startInventoryStub() throws IOException {
        inventoryStub = HttpServer.create(new InetSocketAddress(0), 0);
        inventoryStub.createContext("/inventory/stock", exchange -> {
            byte[] body = "{\"available\":50}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        inventoryStub.start();
    }

    @AfterAll
    static void stopInventoryStub() {
        inventoryStub.stop(0);
    }

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("external.inventory.url",
                () -> "http://localhost:" + inventoryStub.getAddress().getPort());
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserRepository users;

    private String token;

    @BeforeEach
    void setup() {
        token = AuthHelper.obtainToken(rest);
        users.save(new User("u-express", "Express"));
    }

    private ResponseEntity<String> post(String json) {
        return rest.postForEntity("/api/orders",
                new HttpEntity<>(json, AuthHelper.jsonWithAuth(token)), String.class);
    }

    @Test
    void expressWithinStock_returns201() {
        ResponseEntity<String> response =
                post("{\"userId\":\"u-express\",\"amount\":10,\"type\":\"EXPRESS\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void expressExceedingStock_returns409() {
        ResponseEntity<String> response =
                post("{\"userId\":\"u-express\",\"amount\":100,\"type\":\"EXPRESS\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void nonExpress_doesNotCallInventory() {
        // NORMAL 주문은 재고 확인 없이 성공해야 한다 (스텁 의존 없음)
        ResponseEntity<String> response =
                post("{\"userId\":\"u-express\",\"amount\":999,\"type\":\"NORMAL\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }
}
