package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.KafkaExchange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수용-3 (plan Task 5.2): Kafka OTEL SQL 귀속 — 전체 빌드(--trace-mode otel, --with-kafka)에서
 * @KafkaListener consumer가 만든 SQL이 OTEL trace-id로 귀속 캡처되는지 검증한다.
 *
 * <p>order-service를 OTEL 모드로 분석하면 KafkaCaptureRunner가 발행 레코드 헤더에 traceparent를
 * 주입하고(PoC②: consumer process span이 그 child가 됨), 그 trace의 DB span을 환원한다. happy
 * 교환이 order_events INSERT를 페이로드 값 binding과 함께 캡처해야 한다. log 모드 회귀는
 * {@link BuilderIntegrationTest}가 동일 단언을 커버한다. Docker 필요.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class OtelKafkaBuildIntegrationTest {

    @TempDir
    Path out;

    @Test
    void kafkaConsumerSql_capturedViaOtelInFullBuild() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        Path externalStubs = Path.of(System.getProperty("external.stubs"));

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, out,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, externalStubs,
                Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}"),
                null, null, authConfig, false, true, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "otel", null, false));   // ← OTEL SQL 캡처 모드

        // consumer 인덱싱 + happy 교환 존재
        assertThat(asset.kafkaConsumers()).extracting(c -> c.id()).contains("kafka-order-events");
        KafkaExchange happy = asset.kafkaExchanges().stream()
                .filter(e -> e.kafkaConsumerId().equals("kafka-order-events") && !e.variant())
                .findFirst().orElseThrow();

        // happy consumer가 만든 order_events INSERT가 OTEL 경로로 캡처됨 + payload 값이 binding으로 귀속
        var insert = asset.sql().stream()
                .filter(s -> happy.capturedSqlIds().contains(s.id()))
                .filter(s -> s.sqlKind().equals("INSERT") && s.tableName().equals("order_events"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("kafka consumer order_events INSERT not captured via OTEL"));
        assertThat(insert.bindings())
                .as("INSERT bindings(페이로드 값) 귀속됨")
                .anyMatch(b -> b.value() != null && !b.value().isBlank());
    }
}
