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
import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.PathContext;
import io.graphrag.generator.core.TestSynthesizer;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
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
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 합성기 통합 E2E:
 * - SUT의 외부 HTTP 호출 endpoint를 실 호출
 * - capture (SQL + HTTP)
 * - PathContext 구성 후 TestSynthesizer.synthesizeMulti 호출
 * - 생성된 코드에 stubFor(...) + INSERT 모두 포함 확인
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase2HttpSynthesisE2eTest {

    static WireMockServer inventoryMock;

    @BeforeAll
    static void startMock() {
        inventoryMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        inventoryMock.start();
    }

    @AfterAll
    static void stopMock() {
        if (inventoryMock != null) inventoryMock.stop();
    }

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:p2synth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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

    private final Endpoint endpoint = new Endpoint(
            "POST:/api/orders/with-inventory", HttpMethod.POST,
            "/api/orders/with-inventory",
            "demo-sut", "OrdersController", "createWithInventory",
            false, List.of());

    @BeforeEach
    void seed() {
        userRepo.save(new UserEntity("u-1", "John"));
        inventoryMock.resetAll();
    }

    @AfterEach
    void cleanCtx() {
        CaptureContext.clear();
    }

    @Test
    void captureAndSynthesizeProducesCompilableTestWithStub() {
        inventoryMock.stubFor(get(urlMatching("/inventory/stock.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"available\":50}")));

        CaptureContext ctx = new CaptureContext("p1");
        CaptureContext.set(ctx);

        try {
            CreateOrderRequest req = new CreateOrderRequest("u-1", 10L, "EXPRESS");
            mvc.perform(post("/api/orders/with-inventory")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }

        new WireMockHttpRecorder(inventoryMock).captureIntoContext("inventory");

        List<CapturedSql> sqls = ctx.capturedSql();
        List<CapturedHttpCall> httpCalls = ctx.capturedHttpCalls();
        assertThat(httpCalls).isNotEmpty();
        assertThat(sqls).anyMatch(s -> s.rawSql().toLowerCase().contains("insert into orders"));

        ExploredPath path = new ExploredPath(
                "p1", endpoint.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(),
                        Map.of("userId", "u-1", "amount", 10, "type", "EXPRESS")),
                null, List.of(), 201, null, "cov-p1", "v1");

        String generated = TestSynthesizer.synthesizeMulti(new MultiPathSynthesisInput(
                endpoint,
                List.of(new PathContext(path, sqls, httpCalls)),
                "io.graphrag.demo.generated"));

        assertThat(generated)
                .contains("class WithInventoryPostTest")
                .contains("import com.github.tomakehurst.wiremock.client.WireMock;")
                .contains("stubFor(")
                .contains("/inventory/stock")
                .contains("INSERT INTO orders".toLowerCase())   // case-insensitive 비교
                .containsIgnoringCase("INSERT INTO orders");
    }
}
