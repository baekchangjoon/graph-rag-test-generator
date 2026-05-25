package io.graphrag.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.builder.capture.CapturedSqlListener;
import io.graphrag.builder.persistence.GraphArchive;
import io.graphrag.demo.api.CreateOrderRequest;
import io.graphrag.demo.domain.UserEntity;
import io.graphrag.demo.domain.UserRepository;
import io.graphrag.generator.core.SynthesisInput;
import io.graphrag.generator.core.TestSynthesizer;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 0 E2E: 전체 사이클 (capture → archive → synthesize) 검증.
 *
 * <p>분석 환경은 H2 (PostgreSQL 호환 모드 미사용; 기본 H2 + JPA dialect 사용). Phase 1+에서
 * Testcontainers 운영 동일 DBMS로 강화.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql"
})
class Phase0E2eTest {

    @TestConfiguration
    static class ProxyDsConfig {
        // BeanPostProcessor로 사후 wrap: 순환 의존성 회피
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
    void preSeedUser() {
        userRepo.save(new UserEntity("u-1", "John"));
    }

    @AfterEach
    void cleanContext() {
        CaptureContext.clear();
    }

    @Test
    void captureSynthesizeArchiveCycle() throws Exception {
        // 1. 캡처 컨텍스트 활성
        CaptureContext ctx = new CaptureContext("path-1");
        CaptureContext.set(ctx);

        // 2. 실제 endpoint 호출
        CreateOrderRequest req = new CreateOrderRequest("u-1", 100L, "EXPRESS");
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // 3. SQL이 캡처됐는지 확인
        List<CapturedSql> captured = ctx.capturedSql();
        assertThat(captured).isNotEmpty();
        assertThat(captured).anyMatch(s -> s.rawSql().toLowerCase().contains("insert into orders"));

        // 4. Graph archive에 저장
        Path tmp = Files.createTempDirectory("e2e-archive-");
        GraphArchive archive = new GraphArchive(tmp);
        Endpoint endpoint = new Endpoint(
                "POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo-sut", "OrdersController", "create", false, List.of());
        archive.addEndpoint(endpoint);
        captured.forEach(archive::addCapturedSql);
        archive.save();

        // 5. 저장된 archive 다시 로드해서 일관성 확인
        GraphArchive loaded = GraphArchive.load(tmp);
        assertThat(loaded.endpoints()).hasSize(1);
        assertThat(loaded.capturedSqlByPath("path-1")).isNotEmpty();

        // 6. test-generator로 테스트 코드 합성
        String generatedJava = TestSynthesizer.synthesize(new SynthesisInput(
                endpoint,
                loaded.capturedSqlByPath("path-1"),
                "io.graphrag.demo.generated"));

        // 7. 생성된 코드 검증
        assertThat(generatedJava)
                .contains("package io.graphrag.demo.generated;")
                .contains("class OrdersPostTest")
                .contains("@Test")
                .contains(".post(\"/api/orders\")")
                .contains("UUID.randomUUID()");

        // 8. 캡처된 INSERT가 합성된 코드의 fixture에 포함
        boolean hasOrderInsertFixture = generatedJava.contains("INSERT INTO orders")
                || generatedJava.contains("insert into orders");
        assertThat(hasOrderInsertFixture)
                .as("synthesized code should contain the captured INSERT for orders")
                .isTrue();
    }
}
