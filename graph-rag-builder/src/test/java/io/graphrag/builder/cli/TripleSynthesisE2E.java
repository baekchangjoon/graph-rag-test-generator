package io.graphrag.builder.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

        assertThat(notes)
                .as("오라클 플래그를 주지 않은 실행은 그 축소 사실을 notes.md에 남겨야 한다(조용한 축소 금지)")
                .contains("input-oracle: none");
    }

    @Test
    @DisplayName("REQ-032: provenance → synthesize-triple(--sut-jar) CLI 경로에서 concolic 해가 "
            + "cand body에 결정값으로 배치된다(score*2==84 → body.score=42)")
    void req032_cliPipelinePlacesConcolicSolutionInCandidateBody() throws Exception {
        // 수용기준 문면("provenance + synthesize-triple 실행") 그대로 재현한다 — 리포트도 CLI가 만들고,
        // 오라클도 CLI가 --sut-jar에서 실제 ConcolicOracle로 만든다(테스트가 값을 주입하지 않는다).
        Path fixtureSrc = Path.of("src/test/resources/provenance-fixtures/derived");
        Path reportFile = tempDir.resolve("derived-provenance.json");

        BuilderCli.main(new String[] {
                "provenance",
                "--sut-src", fixtureSrc.toString(),
                "--endpoint", "POST /api/derived",
                "--provenance-depth", "3",
                "--out", reportFile.toString()
        });

        JsonNode report = Json.mapper().readTree(reportFile.toFile());
        JsonNode derivedOperand = null;
        for (JsonNode guard : report.path("guards")) {
            for (JsonNode operand : guard.path("operands")) {
                if ("DERIVED".equals(operand.path("origin").asText())) {
                    derivedOperand = operand;
                }
            }
        }
        assertThat(derivedOperand)
                .as("전제: provenance CLI가 DERIVED 피연산자를 산출해야 한다 — 리포트: " + report)
                .isNotNull();
        List<String> derivedRoots = new ArrayList<>();
        derivedOperand.path("derivedFrom").forEach(node -> derivedRoots.add(node.asText()));
        assertThat(derivedRoots)
                .as("전제: DERIVED 피연산자가 파생 루트 필드 score를 derivedFrom에 담아야 한다")
                .containsExactly("score");

        Path bootJar = tempDir.resolve("derived-sut.jar");
        writeBootJarWith(bootJar, ScoreGuardSut.class);
        Path tripleStore = tempDir.resolve("triples-derived");

        BuilderCli.main(new String[] {
                "synthesize-triple",
                "--report", reportFile.toString(),
                "--triple-store", tripleStore.toString(),
                "--sut-jar", bootJar.toString()
        });

        Path endpointDir = tripleStore.resolve(report.get("endpointId").asText());
        assertThat(endpointDir).as("엔드포인트 후보 디렉토리가 생성되어야 한다").isDirectory();

        List<Path> candidateDirs;
        try (var dirs = Files.list(endpointDir)) {
            candidateDirs = dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("cand-"))
                    .sorted()
                    .toList();
        }
        assertThat(candidateDirs).as("cand-NN 후보가 하나 이상 생성되어야 한다").isNotEmpty();

        boolean placed = false;
        for (Path candDir : candidateDirs) {
            JsonNode body = Json.mapper().readTree(candDir.resolve("body.json").toFile());
            if (body.path("score").isNumber() && body.path("score").asLong() == 42L) {
                placed = true;
                assertThat(Files.readString(candDir.resolve("notes.md")))
                        .as("결정값 배치 근거와 오라클 출처가 notes.md에 남아야 한다")
                        .contains("derived(score) -> 오라클 결정값 42")
                        .contains("input-oracle: concolic-asm-z3(--sut-jar)");
            }
        }
        assertThat(placed)
                .as("DERIVED 파생 루트(score)에 concolic 해 42가 JSON 숫자 결정값으로 배치된 후보가 "
                        + "CLI 산출물에 있어야 한다 — 후보 body들: " + candidateDirs)
                .isTrue();
    }

    /** {@code type}의 바이트코드를 Spring Boot 실행형 jar 레이아웃({@code BOOT-INF/classes/})으로 담은 jar. */
    private static void writeBootJarWith(Path jarPath, Class<?> type) throws IOException {
        String binaryPath = type.getName().replace('.', '/') + ".class";
        byte[] classBytes;
        try (InputStream in = type.getClassLoader().getResourceAsStream(binaryPath)) {
            if (in == null) {
                throw new IOException("class bytes not found on test classpath: " + binaryPath);
            }
            classBytes = in.readAllBytes();
        }
        try (OutputStream out = Files.newOutputStream(jarPath);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("BOOT-INF/classes/" + binaryPath));
            zip.write(classBytes);
            zip.closeEntry();
        }
    }


    @Test
    @DisplayName("REQ-005: --graph로 물리 스키마를 주면 EXISTS 가드가 seed.sql INSERT로 배치된다")
    void req005_graphAssetSchemaEnablesExistsSeedPlacement() throws Exception {
        // 물리 스키마가 없으면 합성기는 PK 컬럼을 몰라 EXISTS 가드의 seed 배치를 건너뛴다.
        // 스키마는 build가 이미 graph.json에 캡처해 두므로, 그 자산을 입력으로 받으면 된다.
        Path reportFile = tempDir.resolve("provenance-report.json");
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("golden/provenance-post-api-transfers.json")) {
            Files.copy(in, reportFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Path graphDir = Files.createDirectories(tempDir.resolve("graph"));
        io.graphrag.model.GraphAsset asset = new io.graphrag.model.GraphAsset(
                "order-service", "test-sha", List.of(), List.of(), List.of(),
                List.of(new io.graphrag.model.TableSchema("fund_accounts", List.of(
                        new io.graphrag.model.ColumnSchema("id", "VARCHAR", false, true, false),
                        new io.graphrag.model.ColumnSchema("balance_amount", "BIGINT", false, false, false)),
                        List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null);
        Files.writeString(graphDir.resolve("graph.json"),
                Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(asset));
        Path tripleStore = tempDir.resolve("triples-with-schema");

        BuilderCli.main(new String[] {
                "synthesize-triple",
                "--report", reportFile.toString(),
                "--graph", graphDir.toString(),
                "--triple-store", tripleStore.toString()
        });

        Path candDir = tripleStore.resolve("post-api-transfers").resolve("cand-01");
        // 스키마가 없으면 INSERT가 `(balance_amount)`뿐이라 PK가 빠진다 — NOT NULL PK 테이블에서는
        // 실행조차 되지 않는 SQL이다. EXISTS 가드가 배치되면 PK 컬럼이 함께 들어가야 한다.
        assertThat(Files.readString(candDir.resolve("seed.sql")))
                .as("스키마를 알면 EXISTS 가드가 PK 컬럼과 함께 배치돼야 한다")
                .containsIgnoringCase("insert into")
                .contains("fund_accounts")
                .contains("id");
        assertThat(Files.readString(candDir.resolve("notes.md")))
                .as("PK를 해결했으므로 seed 배치 skip 사유가 남으면 안 된다")
                .doesNotContain("대상 테이블/PK 미해결");
    }

    /** derived 픽스처 {@code ScoreRequest}와 동일 형상의 접근자 홀더. */
    public static final class ScoreHolder {
        private final Integer score;

        public ScoreHolder(Integer score) {
            this.score = score;
        }

        public Integer getScore() {
            return score;
        }
    }

    /** derived 픽스처 {@code create}와 동일한 선형 파생 가드({@code score * 2 == 84})의 바이트코드 원본. */
    public static final class ScoreGuardSut {
        public static String create(ScoreHolder req) {
            if (req.getScore() * 2 == 84) {
                throw new IllegalStateException("score threshold breached");
            }
            return "OK";
        }
    }
}
