package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class BuilderCollectionE2eTest {
    @TempDir Path out;

    @Test
    void httpCollectionBody_exploredWithArrayAndCapturedSql() throws Exception {
        GraphAsset asset = build();
        assertThat(asset.endpoints()).extracting(Endpoint::id).contains("post-api-orders-batch");
        List<ExploredPath> batch = asset.paths().stream()
                .filter(p -> p.id().startsWith("post-api-orders-batch")).toList();
        assertThat(batch).as("batch explored (not skipped)").isNotEmpty();
        assertThat(batch).anyMatch(p -> p.sampleInput() != null && p.sampleInput().isArray());
        ExploredPath happy = batch.stream().filter(p -> p.expectedStatus()/100==2).findFirst().orElseThrow();
        List<CapturedSql> sql = asset.sql().stream().filter(s -> s.pathId().equals(happy.id())).toList();
        assertThat(sql).anyMatch(s -> s.sqlKind().equals("INSERT") && s.tableName().equals("orders"));
        assertThat(sql.stream().flatMap(s -> s.bindings().stream()))
                .anyMatch(b -> b.origin() == BindingOrigin.API_PARAM);
        assertThat(asset.paths()).anyMatch(p -> p.id().startsWith("post-api-orders-by-ids")
                && p.sampleInput() != null && p.sampleInput().isArray());
    }

    @Test
    void kafkaCollectionPayload_capturedAsArray() throws Exception {
        GraphAsset asset = build();
        assertThat(asset.kafkaConsumers()).extracting(KafkaConsumer::topic).contains("order.events.batch");
        var ex = asset.kafkaExchanges().stream()
                .filter(e -> e.kafkaConsumerId().contains("batch") && !e.variant()).findFirst().orElseThrow();
        assertThat(ex.payload().isArray()).isTrue();
    }

    private GraphAsset build() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        AuthConfig auth = new AuthConfig("/api/auth/login","admin","password",
                "token","Authorization","Bearer", List.of());
        return BuilderCli.build(new BuildConfig(
                sutSrc, sutSrc.resolveSibling("resources"), Path.of(System.getProperty("sut.jar")), out,
                "order-service","test",
                new DbConfig(DbConfig.Type.POSTGRES,"postgres:15","app","app","app"),
                60, null, Path.of(System.getProperty("external.stubs")),
                Map.of("EXTERNAL_INVENTORY_URL","{{wiremock}}"),
                null, null, auth, false, true, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(), "otel"));
    }
}
