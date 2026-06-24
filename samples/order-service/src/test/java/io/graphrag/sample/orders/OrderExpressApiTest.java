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

    /** 재고 stub 응답 mode. 케이스별로 갈아끼워 switch arm을 단언한다. */
    static volatile String stubMode = "STANDARD";

    /** 재고 stub 응답 available. EXPRESS_ONLY arm의 소진(<=0) 분기 단언용. */
    static volatile int stubAvailable = 50;

    @BeforeAll
    static void startInventoryStub() throws IOException {
        inventoryStub = HttpServer.create(new InetSocketAddress(0), 0);
        inventoryStub.createContext("/inventory/stock", exchange -> {
            byte[] body = ("{\"available\":" + stubAvailable + ",\"mode\":\"" + stubMode + "\"}").getBytes();
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
        stubMode = "STANDARD";
        stubAvailable = 50;
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

    @Test
    void expressOnlyMode_withStock_returns201() {
        // EXPRESS_ONLY 재고 + 재고 있음(available>0) → express 주문 허용(201).
        stubMode = "EXPRESS_ONLY";
        stubAvailable = 50;
        ResponseEntity<String> response =
                post("{\"userId\":\"u-express\",\"amount\":10,\"type\":\"EXPRESS\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void expressOnlyMode_depletedStock_returns409() {
        // EXPRESS_ONLY 재고가 소진(available<=0) → 거절(409). EXPRESS_ONLY arm의 의미 있는 분기.
        stubMode = "EXPRESS_ONLY";
        stubAvailable = 0;
        ResponseEntity<String> response =
                post("{\"userId\":\"u-express\",\"amount\":10,\"type\":\"EXPRESS\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void backorderMode_returns409() {
        stubMode = "BACKORDER";
        ResponseEntity<String> response =
                post("{\"userId\":\"u-express\",\"amount\":10,\"type\":\"EXPRESS\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }
}
