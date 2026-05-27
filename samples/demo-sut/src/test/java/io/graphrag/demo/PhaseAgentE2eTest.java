package io.graphrag.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.builder.capture.CaptureContextRegistry;
import io.graphrag.builder.capture.JdbcAgentBaggageBridge;
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
import io.jdbcintercept.api.JdbcCaptureSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Option A end-to-end via jdbc-intercept-agent (no DataSource BeanPostProcessor).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>JVM has {@code -javaagent:jdbc-intercept-agent-core.jar} attached (configured in
 *       {@code samples/demo-sut/build.gradle.kts test} task)</li>
 *   <li>Agent's {@code ListenerRegistry} discovers
 *       {@link JdbcAgentBaggageBridge} via {@code META-INF/services}</li>
 *   <li>Test calls {@link JdbcCaptureSession#begin(String)} to mark the path-id +
 *       {@link CaptureContextRegistry#register} to associate it with a graph-rag
 *       {@link CaptureContext}</li>
 *   <li>SUT request → JPA → JDBC → agent advice fires → bridge → ctx accumulates
 *       {@link CapturedSql} (with {@code readResultRows} for SELECTs via Option A)</li>
 *   <li>{@link FixtureComposer} turns the captured SELECT snapshot into seed INSERTs
 *       + auto cleanup DELETEs</li>
 *   <li>{@link TestSynthesizer} produces a runnable test source containing both</li>
 * </ol>
 *
 * <p>Notably this test does <em>not</em> use {@code ProxyDataSource} or any
 * {@code BeanPostProcessor}. SUT code/config remain untouched aside from the JVM arg.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql"
})
class PhaseAgentE2eTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepo;

    private static final String PATH_ID = "agent-path-1";
    private CaptureContext ctx;

    @BeforeEach
    void seedAndBeginCapture() {
        userRepo.save(new UserEntity("u-agent", "Agent George"));
        ctx = new CaptureContext(PATH_ID);
        CaptureContextRegistry.register(PATH_ID, ctx);
        JdbcCaptureSession.begin(PATH_ID);   // ThreadLocal — agent advice will see this
    }

    @AfterEach
    void cleanup() {
        JdbcCaptureSession.end();
        CaptureContextRegistry.unregister(PATH_ID);
    }

    @Test
    void agent_capture_via_serviceLoader_bridge_produces_select_snapshot_and_fixture() throws Exception {
        // 1. Endpoint call — runs on the same (test) thread under MockMvc, so JdbcCaptureSession
        //    ThreadLocal is visible to the agent's ExecuteAdvice.
        CreateOrderRequest req = new CreateOrderRequest("u-agent", 100L, "EXPRESS");
        mvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
            .andExpect(status().isCreated());

        // 2. captured SQL must include SELECT users (via findById) with non-empty snapshot rows
        List<CapturedSql> captured = ctx.capturedSql();
        assertThat(captured).as("agent → bridge → ctx").isNotEmpty();

        CapturedSql userSelect = captured.stream()
                .filter(s -> s.type() == CapturedSqlType.SELECT
                          && s.rawSql().toLowerCase().contains("users"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "expected SELECT on users via agent, got: "
                    + captured.stream().map(CapturedSql::rawSql).toList()));

        assertThat(userSelect.readResultRows())
                .as("Option A snapshot rows via agent SnapshotResultSetWrapper")
                .isNotEmpty();
        assertThat(userSelect.readResultRows().get(0))
                .containsKey("ID");

        // 3. FixtureComposer → snapshot row → INSERT INTO users
        List<FixtureStatement> fixtures = FixtureComposer.fromCapturedSqls(captured);
        boolean hasUserSeedInsert = fixtures.stream()
                .anyMatch(f -> f.sql().toUpperCase().contains("INSERT INTO USERS")
                            && f.params().contains("u-agent"));
        assertThat(hasUserSeedInsert)
                .as("agent-captured SELECT snapshot → INSERT seed fixture")
                .isTrue();

        boolean hasOrderInsert = fixtures.stream()
                .anyMatch(f -> f.sql().toLowerCase().contains("insert into orders"));
        assertThat(hasOrderInsert)
                .as("agent-captured INSERT orders also present (no Option A required)")
                .isTrue();

        // 4. TestSynthesizer → final generated test contains both before-INSERTs
        Endpoint endpoint = new Endpoint(
                "POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo-sut", "OrdersController", "create", false, List.of());
        String generated = TestSynthesizer.synthesize(new SynthesisInput(
                endpoint, captured, "io.graphrag.demo.gen.agent"));
        assertThat(generated)
                .containsIgnoringCase("INSERT INTO users")
                .contains("\"u-agent\"")
                .containsIgnoringCase("INSERT INTO orders")
                .contains(".post(\"/api/orders\")")
                .containsIgnoringCase("DELETE FROM users")
                .containsIgnoringCase("DELETE FROM orders");
    }
}
