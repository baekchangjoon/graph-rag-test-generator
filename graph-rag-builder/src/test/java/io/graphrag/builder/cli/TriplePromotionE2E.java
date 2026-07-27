package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.oracle.LlmOptions;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-020(+REQ-035은 {@code BuilderCliStaleTripleTest} 별도): {@code --triple-candidates}로 소비되는
 * promoted 후보가 SUT와 불일치(trial 재확인 실패)할 때 staleTriples로 표면화하고 후보 부재 시와 동일
 * 산출로 회귀하는지 실 order-service(Testcontainers Postgres + fixture SUT jar)로 검증한다.
 *
 * <p>{@link FixtureBaselineE2E}와 같은 부팅 계약(-Dsut.jar/-Dsut.src 시스템 프로퍼티)을 재사용한다.
 * REQ-018(promoted 완주 경로 — 확정 run 성공 + 생성 TC green)의 수용 테스트는 Task 18에서 이 클래스에
 * 추가된다(브리핑 각주 — "Task 18의 REQ-018 메서드와 공존").
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class TriplePromotionE2E {

    @TempDir
    Path out;

    @TempDir
    Path tripleRoot;

    @Test
    @DisplayName("REQ-020: SUT와 불일치하는 promoted 후보는 trial 재확인 실패 → staleTriples 표면화 + 후보 부재와 동일 산출로 회귀")
    void req020_staleTripleOnTrialMismatchFallsBackToBaseline() throws Exception {
        seedStaleTripleCandidate();

        GraphAsset graph = buildOrderServiceGraph(tripleRoot);

        // 나머지 산출물은 후보 부재 시와 동일 — transfers는 여전히 2xx 미도달(REQ-028 outer red와 동일 상태).
        List<ExploredPath> transferPaths = graph.paths().stream()
                .filter(p -> p.endpointId().equals("post-api-transfers")).toList();
        assertThat(transferPaths).as("endpoint 자체가 회귀로 사라지지 않았어야 한다").isNotEmpty();
        assertThat(transferPaths).noneMatch(p -> p.expectedStatus() / 100 == 2
                && p.outcome() == Outcome.Kind.SUCCESS);

        // staleTriples 표면화(REQ-020, REQ-021 포맷: <endpointId>/promoted/cand-NN).
        ExplorationReport report = Json.mapper().readValue(
                out.resolve("exploration-report.json").toFile(), ExplorationReport.class);
        ExplorationReport.EndpointExploration transferEntry = report.endpoints().stream()
                .filter(e -> e.endpointId().equals("post-api-transfers"))
                .findFirst().orElseThrow(() -> new AssertionError("post-api-transfers entry missing from report"));
        assertThat(transferEntry.staleTriples())
                .as("REQ-020/021: 재확인 실패 후보가 <endpointId>/promoted/cand-NN 포맷으로 표면화되어야 한다")
                .containsExactly("post-api-transfers/promoted/cand-01");
        assertThat(transferEntry.trialCount()).as("게이트가 정확히 1회 발화했어야 한다").isEqualTo(1);
        assertThat(transferEntry.tripleAdopted()).as("재확인 실패 후보는 채택되지 않아야 한다").isFalse();
    }

    /**
     * 실 SUT와 불일치하는 promoted 후보를 고정 배치한다 — balance_amount(10) &lt; amount(100)이라
     * account 존재 가드(①)는 통과하고 잔액 가드(②)에서 422(잔액 부족)로 trial invoke가 실패한다.
     * base/promoted 내용을 완전히 동일하게 둬 T1 마커-diff는 항상 통과(변경 없음 — REQ-009 자명
     * 충족)시키고, 재확인 실패라는 REQ-020 트리거만 격리한다. {@code items}는 의도적으로 생략한다 —
     * {@link io.graphrag.builder.index.BodyShape}는 최상위 필드만 평평하게 나열해(중첩 리스트를
     * {@code items.sku}/{@code items.qty} 같은 dot-path로 전개하지 않음) T1
     * {@code schemaViolationsForBody}의 허용 집합에 없고, 어차피 잔액 가드가 items 가드보다 먼저
     * 평가되므로 없어도 이 시나리오(422)에는 영향이 없다.
     */
    private void seedStaleTripleCandidate() throws Exception {
        Path endpointDir = Files.createDirectories(tripleRoot.resolve("post-api-transfers"));
        String body = "{\"fromAccountId\":\"ACC-STALE-1\",\"amount\":100,\"note\":\"stale-note\"}";
        String seed = "INSERT INTO fund_accounts (id, balance_amount) VALUES ('ACC-STALE-1', 10);";
        String stubs = "{}";
        for (String bucket : List.of("promoted", "base")) {
            Path candDir = Files.createDirectories(endpointDir.resolve(bucket).resolve("cand-01"));
            Files.writeString(candDir.resolve("body.json"), body);
            Files.writeString(candDir.resolve("seed.sql"), seed);
            Files.writeString(candDir.resolve("stubs.json"), stubs);
        }
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("golden/provenance-post-api-transfers.json")) {
            Files.copy(in, endpointDir.resolve("provenance-report.json"));
        }
    }

    /**
     * REQ-018(완주): 커밋된 {@code e2e/triples/post-api-transfers/promoted/cand-01}(Task 18 부트스트랩
     * — 사람 갭필, spec §10 허용)이 실 SUT와 일치해 T1 재검증 + trial 재확인을 통과 → 확정 run(캡처-on
     * 재explore)까지 채택되는지 검증한다. {@code e2e/triples}는 임시 디렉터리가 아니라 이 저장소에
     * 커밋된 실 e2e fixture다({@code build.gradle.kts}의 {@code -Dtriple.candidates} 시스템 프로퍼티로
     * 주입, {@code external.stubs}와 동일 관례).
     *
     * <p>후보 내용(seed {@code fund_accounts}, stub {@code POST /fraud/check -> CLEAR}, body
     * {@code fromAccountId/amount/items[0].{sku,qty}}) 근거는
     * {@code e2e/triples/post-api-transfers/promoted/cand-01/notes.md} 참조 — 이 후보가 채택되면
     * 컨트롤러의 4개 분기(계좌 존재/잔액/items 유효성/fraud 통과)를 모두 통과해 201을 반환해야 한다.
     */
    @Test
    @DisplayName("REQ-018: 커밋된 promoted 후보(Task 18 부트스트랩)가 실 SUT와 일치 → T1 재검증+trial 재확인 통과 "
            + "→ 확정 run으로 채택되고 graph.json에 post-api-transfers의 2xx SUCCESS ExploredPath가 남는다")
    void req018_adoptedTripleProducesSuccessExploredPath() throws Exception {
        Path tripleCandidatesRoot = Path.of(System.getProperty("triple.candidates"));

        GraphAsset graph = buildOrderServiceGraph(tripleCandidatesRoot);

        List<ExploredPath> transferPaths = graph.paths().stream()
                .filter(p -> p.endpointId().equals("post-api-transfers")).toList();
        assertThat(transferPaths).as("post-api-transfers 엔드포인트가 그래프에 있어야 한다").isNotEmpty();
        assertThat(transferPaths)
                .as("REQ-018: 채택된 promoted 후보로 2xx SUCCESS ExploredPath가 남아야 한다: " + transferPaths)
                .anyMatch(p -> p.expectedStatus() / 100 == 2 && p.outcome() == Outcome.Kind.SUCCESS);

        ExplorationReport report = Json.mapper().readValue(
                out.resolve("exploration-report.json").toFile(), ExplorationReport.class);
        ExplorationReport.EndpointExploration transferEntry = report.endpoints().stream()
                .filter(e -> e.endpointId().equals("post-api-transfers"))
                .findFirst().orElseThrow(() -> new AssertionError("post-api-transfers entry missing from report"));
        assertThat(transferEntry.tripleAdopted())
                .as("REQ-018: promoted 후보가 채택돼야 한다(tripleAdopted=true)").isTrue();
        assertThat(transferEntry.staleTriples())
                .as("채택된 후보는 staleTriples로 표면화되면 안 된다").isEmpty();
        assertThat(transferEntry.trialCount()).as("게이트가 정확히 1회 발화했어야 한다").isEqualTo(1);
    }

    /**
     * {@link FixtureBaselineE2E#buildOrderServiceGraph()}와 동일한 부팅 계약 — 여기에
     * {@code tripleCandidatesRoot}만 추가로 배선한다(REQ-018/019/020/035 게이트 활성화).
     */
    private GraphAsset buildOrderServiceGraph(Path tripleCandidatesRoot) throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        return BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, out,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null,
                Path.of(System.getProperty("external.stubs")),
                Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}", "EXTERNAL_FRAUD_URL", "{{wiremock}}"),
                null, null, authConfig, false, true, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(), "none", null, false, true,
                LlmOptions.disabled(), null, 1, null, null, 0, 0L, 0L,
                tripleCandidatesRoot));
    }
}
