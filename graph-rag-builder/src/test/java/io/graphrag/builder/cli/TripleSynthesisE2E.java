package io.graphrag.builder.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-005: {@code synthesize-triple} CLI E2E — golden provenance 리포트({@code
 * golden/provenance-post-api-transfers.json}, Task 1~7 산출물)를 입력으로 CLI를 실행해 cand-01
 * 후보 디렉토리(body.json/seed.sql/stubs.json/notes.md)가 산출되고, 결정값마다 가드 위치 trace가
 * notes.md에 남는지 검증한다. SUT 부팅·소스 인덱싱이 필요 없는 순수 리포트→후보 변환 경로다.
 */
class TripleSynthesisE2E {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("REQ-005: synthesize-triple CLI가 golden 리포트로 cand-01/{body.json,seed.sql,stubs.json,notes.md}를 "
            + "산출하고 notes.md에 가드 위치 trace를 남긴다")
    void req005_cliProducesCandidateDirectoryWithTrace() throws Exception {
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

        Path cand01 = tripleStore.resolve("post-api-transfers").resolve("cand-01");
        assertThat(cand01).as("cand-01 후보 디렉토리가 생성되어야 한다").isDirectory();
        Path bodyFile = cand01.resolve("body.json");
        Path seedFile = cand01.resolve("seed.sql");
        Path stubsFile = cand01.resolve("stubs.json");
        Path notesFile = cand01.resolve("notes.md");
        assertThat(bodyFile).as("body.json이 생성되어야 한다").exists();
        assertThat(seedFile).as("seed.sql이 생성되어야 한다").exists();
        assertThat(stubsFile).as("stubs.json이 생성되어야 한다").exists();
        assertThat(notesFile).as("notes.md가 생성되어야 한다").exists();

        JsonNode body = Json.mapper().readTree(bodyFile.toFile());
        assertThat(body.get("amount").asLong())
                .as("비교 가드(balance < amount)의 negate(GE) 만족값이 body.amount에 결정적으로 배치되어야 한다")
                .isEqualTo(100L);
        assertThat(body.get("note").asText())
                .as("가드 없는 free-text unguarded 필드는 갭 마커로 표기되어야 한다(REQ-007)")
                .isEqualTo("__AGENT_FILL__{type:String, semanticHint:free-text, guard:none}");

        String seedSql = Files.readString(seedFile);
        assertThat(seedSql)
                .as("DB_READ 출처 비교 가드의 결정값이 fund_accounts seed INSERT에 배치되어야 한다")
                .contains("INSERT INTO fund_accounts")
                .contains("balance_amount");

        String stubsJson = Files.readString(stubsFile);
        StubMapping mapping = StubMapping.buildFrom(stubsJson);   // 예외 없이 로드되어야 함(REQ-008)
        assertThat(mapping.getRequest().getUrlPath()).isEqualTo("/fraud/check");
        assertThat(mapping.getResponse().getJsonBody().get("status").asText()).isEqualTo("CLEAR");

        String notes = Files.readString(notesFile);
        assertThat(notes)
                .as("notes.md에 각 결정값의 가드 위치(trace) 근거가 남아야 한다")
                .contains("cand-01")
                .contains("TransferController.java:30")
                .contains("TransferController.java:37")
                .contains("body.amount=100");
    }
}
