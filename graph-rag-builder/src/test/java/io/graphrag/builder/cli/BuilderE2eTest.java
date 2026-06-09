package io.graphrag.builder.cli;

import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.SqlBinding;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 order-service jar에 대한 빌더 전 사이클. Docker 필요. */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class BuilderE2eTest {

    @TempDir
    Path out;

    @Test
    void build_capturesEndpointPathSqlAndSchema() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));

        GraphAsset asset = BuilderCli.build(sutSrc, sutJar, out, "order-service", "test", "postgres:15");

        assertThat(Files.exists(out.resolve("graph.json"))).isTrue();

        // endpoint
        assertThat(asset.endpoints()).extracting(e -> e.id()).containsExactly("post-api-orders");

        // explored path: happy path 201 + 응답 status=PENDING
        assertThat(asset.paths()).hasSize(1);
        ExploredPath path = asset.paths().get(0);
        assertThat(path.expectedStatus()).isEqualTo(201);
        assertThat(path.sampleResponse().get("status").asText()).isEqualTo("PENDING");
        assertThat(path.sampleInput().get("userId").asText()).isEqualTo("probe-userId");

        // captured SQL: orders INSERT가 있고 origin이 구분된다
        CapturedSql insert = asset.sql().stream()
                .filter(s -> s.sqlKind().equals("INSERT") && s.tableName().equals("orders"))
                .findFirst().orElseThrow();
        assertThat(insert.bindings())
                .filteredOn(b -> b.column().equals("user_id"))
                .extracting(SqlBinding::origin).containsExactly(BindingOrigin.API_PARAM);
        assertThat(insert.bindings())
                .filteredOn(b -> b.column().equals("status"))
                .extracting(SqlBinding::origin).containsExactly(BindingOrigin.LITERAL);

        // schema: users/orders + FK
        assertThat(asset.tables()).extracting(t -> t.name()).contains("users", "orders");
        assertThat(asset.tables().stream().filter(t -> t.name().equals("orders")).findFirst()
                .orElseThrow().foreignKeys())
                .anyMatch(fk -> fk.column().equals("user_id") && fk.referencedTable().equals("users"));
    }
}
