package io.graphrag.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.builder.capture.CapturedSqlListener;
import io.graphrag.demo.api.CreateOrderRequest;
import io.graphrag.demo.domain.UserEntity;
import io.graphrag.demo.domain.UserRepository;
import io.graphrag.generator.compose.FixtureComposer;
import io.graphrag.generator.compose.FixtureStatement;
import io.graphrag.generator.core.SynthesisInput;
import io.graphrag.generator.core.TestSynthesizer;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Option A (docs/12) E2E — seeded data 시나리오:
 *
 * <ol>
 *   <li>data.sql 대신 직접 @BeforeEach 로 user "u-1" 시드 (capture 직전 commit)</li>
 *   <li>CaptureContext 활성화</li>
 *   <li>POST /api/orders → 컨트롤러가 userRepo.findById("u-1") (SELECT) → orderRepo.save (INSERT)</li>
 *   <li>captured 에 SELECT (readResultRows 채워짐) + INSERT 함께 존재 검증</li>
 *   <li>TestSynthesizer 출력에 user seed INSERT + orders INSERT 둘 다 포함 검증</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:opta_e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql"
})
class PhaseSeedDataE2eTest {

    @TestConfiguration
    static class ProxyDsConfig {
        @Bean
        static BeanPostProcessor dataSourceProxyPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource && !(bean instanceof ProxyDataSource)) {
                        return ProxyDataSourceBuilder.create((DataSource) bean)
                                .name("graph-rag-capture-opta")
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
    void seedUser() {
        // capture 활성화 전에 시드 — 이 INSERT 는 capture 에 안 잡힘 (production data.sql 시뮬레이션)
        userRepo.save(new UserEntity("u-1", "George"));
    }

    @AfterEach
    void cleanup() { CaptureContext.clear(); }

    @Test
    void capturesSelectRowSnapshotAndSynthesizesSeedInsert() throws Exception {
        // 1. 캡처 컨텍스트 활성화
        CaptureContext ctx = new CaptureContext("path-seed");
        CaptureContext.set(ctx);

        // 2. POST /api/orders → 내부적으로 SELECT users + INSERT orders
        CreateOrderRequest req = new CreateOrderRequest("u-1", 100L, "EXPRESS");
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // 3. captured 검증
        List<CapturedSql> captured = ctx.capturedSql();
        assertThat(captured).isNotEmpty();

        CapturedSql userSelect = captured.stream()
                .filter(s -> s.type() == CapturedSqlType.SELECT
                          && s.rawSql().toLowerCase().contains("users"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("expected SELECT on users, got: " + captured));

        assertThat(userSelect.readResultRows())
                .as("Option A: SELECT 의 row snapshot 채워짐")
                .hasSize(1);
        Map<String, Object> row = userSelect.readResultRows().get(0);
        // SELECT * 변환 결과 모든 컬럼 (H2 PostgreSQL 모드는 컬럼명 대문자)
        assertThat(row).containsKeys("ID", "NAME");
        assertThat(row.get("ID")).isEqualTo("u-1");
        assertThat(row.get("NAME")).isEqualTo("George");

        // 4. FixtureComposer 가 SELECT 를 INSERT fixture 로 합성하는지
        List<FixtureStatement> fixtures = FixtureComposer.fromCapturedSqls(captured);
        boolean hasSeedUserInsert = fixtures.stream()
                .anyMatch(f -> f.sql().toUpperCase().startsWith("INSERT INTO USERS")
                            && f.params().contains("u-1"));
        assertThat(hasSeedUserInsert)
                .as("seed user 가 SELECT snapshot → INSERT fixture 로 변환됨")
                .isTrue();

        boolean hasOrderInsert = fixtures.stream()
                .anyMatch(f -> f.sql().toLowerCase().contains("insert into orders"));
        assertThat(hasOrderInsert)
                .as("기존 INSERT (orders) 도 함께 fixture 에 포함")
                .isTrue();

        // 5. TestSynthesizer 출력에 user seed INSERT + orders INSERT 모두 포함
        Endpoint endpoint = new Endpoint(
                "POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo-sut", "OrdersController", "create", false, List.of());
        String generated = TestSynthesizer.synthesize(new SynthesisInput(
                endpoint, captured, "io.graphrag.demo.gen.seed"));

        assertThat(generated)
                .containsIgnoringCase("INSERT INTO users")        // SELECT snapshot → INSERT
                .contains("\"u-1\"")
                .contains("\"George\"")
                .containsIgnoringCase("INSERT INTO orders")        // 기존 INSERT
                .contains(".post(\"/api/orders\")")
                .containsIgnoringCase("DELETE FROM users")         // cleanup
                .containsIgnoringCase("DELETE FROM orders");
    }
}
