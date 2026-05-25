package io.graphrag.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.builder.capture.CapturedSqlListener;
import io.graphrag.builder.capture.http.WireMockHttpRecorder;
import io.graphrag.demo.api.CreateOrderRequest;
import io.graphrag.demo.domain.UserEntity;
import io.graphrag.demo.domain.UserRepository;
import io.graphrag.generator.compose.http.HttpStubComposer;
import io.graphrag.model.CapturedHttpCall;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 E2E: 외부 HTTP 호출이 포함된 endpoint의 캡처 + WireMock stub composer 통합 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase2HttpE2eTest {

    static WireMockServer inventoryMock;

    @BeforeAll
    static void startWireMock() {
        inventoryMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        inventoryMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (inventoryMock != null) inventoryMock.stop();
    }

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:phase2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        r.add("spring.datasource.username", () -> "sa");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.sql.init.mode", () -> "always");
        r.add("spring.sql.init.schema-locations", () -> "classpath:schema.sql");
        r.add("external.inventory.url", () -> inventoryMock.baseUrl());
    }

    @TestConfiguration
    static class ProxyDsConfig {
        @Bean
        static BeanPostProcessor dataSourceProxyPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource && !(bean instanceof ProxyDataSource)) {
                        return ProxyDataSourceBuilder.create((DataSource) bean)
                                .name("graph-rag-capture")
                                .listener(new CapturedSqlListener())
                                .build();
                    }
                    return bean;
                }
            };
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepo;

    @BeforeEach
    void seed() {
        userRepo.save(new UserEntity("u-1", "John"));
        inventoryMock.resetAll();
    }

    @AfterEach
    void clean() {
        CaptureContext.clear();
    }

    @Test
    void externalHttpCallCapturedAndStubComposed() throws Exception {
        // 1. WireMock에 inventory 응답 정의
        inventoryMock.stubFor(get(urlMatching("/inventory/stock.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"available\": 50}")));

        CaptureContext ctx = new CaptureContext("path-http");
        CaptureContext.set(ctx);

        // 2. inventory 호출이 포함된 endpoint 실행
        CreateOrderRequest req = new CreateOrderRequest("u-1", 10L, "EXPRESS");
        mvc.perform(post("/api/orders/with-inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // 3. WireMock에서 외부 호출 캡처
        WireMockHttpRecorder recorder = new WireMockHttpRecorder(inventoryMock);
        recorder.captureIntoContext("inventory-service");

        List<CapturedHttpCall> calls = ctx.capturedHttpCalls();
        assertThat(calls).hasSize(1);
        CapturedHttpCall call = calls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.urlConcrete()).contains("/inventory/stock");
        assertThat(call.urlConcrete()).contains("type=EXPRESS");
        assertThat(call.responseStatus()).isEqualTo(200);
        assertThat(call.targetExternalId()).isEqualTo("inventory-service");

        // 4. 합성된 stub 코드 검증
        String stubCode = HttpStubComposer.compose(call);
        assertThat(stubCode)
                .contains("stubFor(")
                .contains("get(urlPathEqualTo(\"/inventory/stock\"))")
                .contains("withQueryParam(\"type\", equalTo(\"EXPRESS\"))")
                .contains("withStatus(200)")
                .contains("available");

        // 5. SQL도 함께 캡처됐는지 (orders INSERT)
        assertThat(ctx.capturedSql())
                .anyMatch(s -> s.rawSql().toLowerCase().contains("insert into orders"));
    }

    @Test
    void insufficientStockReturnsConflict() throws Exception {
        inventoryMock.stubFor(get(urlMatching("/inventory/stock.*"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"available\": 5}")));

        CreateOrderRequest req = new CreateOrderRequest("u-1", 100L, "EXPRESS");
        mvc.perform(post("/api/orders/with-inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }
}
