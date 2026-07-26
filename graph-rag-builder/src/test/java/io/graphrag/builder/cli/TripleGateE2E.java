package io.graphrag.builder.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.provenance.ProvenanceReport;
import io.graphrag.builder.provenance.TripleValidator;
import io.graphrag.builder.provenance.TripleValidator.ValidationResult;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-009 CLI 레벨 E2E — {@code synthesize-triple} CLI가 산출한 실제 {@code cand-NN}/{@code base/cand-NN}
 * 사본을 대상으로 {@link TripleValidator}가 마커-diff를 판정한다: 갭 마커만 채운 후보는 통과, 비-마커
 * 필드를 바꾼 후보는 reject된다. golden 리포트({@code golden/provenance-post-api-transfers.json},
 * Task 1~7 산출물)를 그대로 재사용한다({@link TripleSynthesisE2E}와 동일 소스).
 */
class TripleGateE2E {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("REQ-009: synthesize-triple CLI 산출물에서 마커만 채운 후보는 T1 게이트를 통과하고, "
            + "비-마커 필드를 바꾼 후보는 reject된다")
    void req009_cliProducedCandidateGatedByMarkerDiff() throws Exception {
        Path reportFile = tempDir.resolve("provenance-report.json");
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("golden/provenance-post-api-transfers.json")) {
            Files.copy(in, reportFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Path tripleStore = tempDir.resolve("triples");

        BuilderCli.main(new String[] {
                "synthesize-triple",
                "--report", reportFile.toString(),
                "--triple-store", tripleStore.toString()
        });

        Path endpointDir = tripleStore.resolve("post-api-transfers");
        Path cand01 = endpointDir.resolve("cand-01");
        Path base01 = endpointDir.resolve("base").resolve("cand-01");
        assertThat(base01).as("base/cand-01 사본이 cand-01과 함께 산출되어야 한다(REQ-009 마커-diff 기준본)")
                .isDirectory();
        assertThat(base01.resolve("body.json")).exists();
        assertThat(base01.resolve("seed.sql")).exists();
        assertThat(base01.resolve("stubs.json")).exists();

        // 에이전트가 cand-01의 갭 마커("note")만 채운다 — base/cand-01은 도구 생성 그대로 둔다.
        ObjectNode body = (ObjectNode) Json.mapper().readTree(cand01.resolve("body.json").toFile());
        assertThat(body.get("note").asText())
                .as("cand-01.body.note는 갭 마커여야 한다(REQ-007)")
                .contains("__AGENT_FILL__");
        body.put("note", "checked-by-agent");
        Files.writeString(cand01.resolve("body.json"),
                Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(body));

        ProvenanceReport report = Json.mapper().readValue(reportFile.toFile(), ProvenanceReport.class);
        TripleValidator validator = new TripleValidator(List.of(), DbConfig.Type.POSTGRES);

        ValidationResult accepted = validator.validate(cand01, base01, report, BodyShape.empty());
        assertThat(accepted.accepted())
                .as("갭 마커만 채운 CLI 산출 후보는 T1 게이트를 통과해야 한다: " + accepted.reasons())
                .isTrue();

        // 비-마커 필드(amount)를 임의로 변조하면 reject되어야 한다.
        ObjectNode tampered = (ObjectNode) Json.mapper().readTree(cand01.resolve("body.json").toFile());
        tampered.put("amount", tampered.get("amount").asLong() + 1);
        Files.writeString(cand01.resolve("body.json"),
                Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(tampered));

        ValidationResult rejected = validator.validate(cand01, base01, report, BodyShape.empty());
        assertThat(rejected.accepted())
                .as("비-마커 필드(amount) 변조는 reject되어야 한다")
                .isFalse();
        assertThat(rejected.reasons()).isNotEmpty();
    }
}
