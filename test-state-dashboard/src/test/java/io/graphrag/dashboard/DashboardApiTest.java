package io.graphrag.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardApiTest {

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> postEvent(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/events", new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void eventLifecycle_visibleThroughQueryApi() {
        String created = """
                {"type":"SCOPE_CREATED","testId":"t-api1","runId":"r1",
                 "at":"2026-06-10T00:00:00Z","detail":null}""";
        String inserted = """
                {"type":"DB_ROW_INSERTED","testId":"t-api1","runId":"r1",
                 "at":"2026-06-10T00:00:01Z",
                 "detail":{"table":"users","keyColumn":"id","keyValue":"t-api1-user"}}""";

        assertThat(postEvent(created).getStatusCode().value()).isEqualTo(202);
        assertThat(postEvent(inserted).getStatusCode().value()).isEqualTo(202);

        ResponseEntity<String> active = rest.getForEntity("/active", String.class);
        assertThat(active.getStatusCode().value()).isEqualTo(200);
        assertThat(active.getBody()).contains("t-api1");

        ResponseEntity<String> detail = rest.getForEntity("/test/t-api1", String.class);
        assertThat(detail.getBody()).contains("users").contains("t-api1-user");

        ResponseEntity<String> missing = rest.getForEntity("/test/does-not-exist", String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
    }
}
