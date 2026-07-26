package io.graphrag.builder.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-001: {@code provenance} CLI 서브커맨드 golden E2E — SUT 부팅 없이 순수 정적 경로만으로
 * {@code order-service}의 {@code POST /api/transfers}를 슬라이싱해 provenance 리포트를 산출한다.
 *
 * <p>{@code --sut-src}는 gradle {@code test} 태스크가 주입하는 {@code -Dsut.src}(order-service의
 * {@code src/main/java} 절대경로)를 재사용한다 — 이 경로는 항상 (부트jar 여부와 무관하게) 설정되므로
 * provenance 서브커맨드가 SUT 부팅이 필요 없다는 계약을 그대로 반영한다.
 */
@EnabledIfSystemProperty(named = "sut.src", matches = ".+")
class ProvenanceCliE2E {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("REQ-001: provenance 서브커맨드가 POST /api/transfers를 golden과 동일하게 산출한다")
    void req001_provenanceCliProducesGoldenReport() throws Exception {
        String sutSrc = System.getProperty("sut.src");
        Path outFile = tempDir.resolve("provenance-report.json");

        BuilderCli.main(new String[] {
                "provenance",
                "--sut-src", sutSrc,
                "--endpoint", "POST /api/transfers",
                "--provenance-depth", "3",
                "--out", outFile.toString()
        });

        assertThat(outFile).as("provenance 리포트 파일이 생성되어야 한다").exists();

        JsonNode actual = Json.mapper().readTree(outFile.toFile());
        JsonNode expected = Json.mapper().readTree(
                getClass().getClassLoader().getResourceAsStream("golden/provenance-post-api-transfers.json"));

        assertThat(actual)
                .as("provenance 리포트가 golden(가드 4건: EXISTS/GE-LT/INPUT items.qty/EQ+EXTERNAL_RESPONSE)과 "
                        + "JSON 정규화 비교로 일치해야 한다")
                .isEqualTo(expected);
    }
}
