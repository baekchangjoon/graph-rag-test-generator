package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.parallel.GraphSetEquivDiffTool;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExplorationReport.EndpointExploration;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-022: {@code GRB_TRIAL=off} 또는 게이트 미발화(promoted 후보 부재)일 때 빌더 산출물이 게이트
 * 코드 진입 전(트리플 미적용) 동작과 <b>정규화 비교로 동등</b>함을 고정한다(회귀 0).
 *
 * <h2>정규화 비교 방침</h2>
 * <ul>
 *   <li><b>graph.json</b> — {@link GraphSetEquivDiffTool}(REQ-P003 기존 set-동등 diff 도구)을 그대로
 *       재사용한다. 신규 필드가 {@link GraphAsset}에는 추가되지 않았으므로(신규 관측 필드는
 *       {@link EndpointExploration} 전용) 제외 목록 없이 그대로 diff한다.</li>
 *   <li><b>exploration-report.json의 {@link EndpointExploration}</b> — <b>제외 키
 *       목록</b>(Task 13이 도입한 REQ-021 신규 관측 필드, 게이트 미발화 시 항상 기본값):
 *       {@code trialCount}(기본 0), {@code tripleAdopted}(기본 false), {@code tripleRejected}(기본
 *       빈 맵), {@code staleTriples}(기본 빈 리스트). 이 네 필드를 제외한 나머지
 *       (totalBranches/coveredBranches/missedBranches/pathsByEngine/solverRelevantMissed/
 *       droppedPaths/noHappyPathReason)를 비교한다. {@code missedBranches}/{@code droppedPaths}는
 *       리스트 순서가 아니라 <b>집합</b>으로 비교한다(branch 분석기 순회 순서가 계약이 아님 — 기존
 *       {@link GraphSetEquivDiffTool}과 동일한 set-동등 철학).</li>
 * </ul>
 *
 * <h2>두 조건</h2>
 * <ol>
 *   <li>{@link #req022_grbTrialOffDisablesGateEvenWithPendingCandidate()} — {@code post-api-transfers}
 *       promoted 아래에 {@link TriplePromotionE2E}와 동일한(실제로 STALE을 유발하는, 즉 게이트가 살아
 *       있었다면 trial을 최소 1회 발화시켰을) 후보를 배치해두고 {@code GRB_TRIAL=off}로 빌드한다.
 *       {@link TriplePromotionE2E#req020_staleTripleOnTrialMismatchFallsBackToBaseline()}이 이미
 *       고정한 대로 이 후보는 ablation 없이는 {@code trialCount=1}·{@code staleTriples}非빈을 만든다 —
 *       그 사실을 역이용해, {@code GRB_TRIAL=off}에서는 {@code trialCount=0}(게이트가 아예 호출되지
 *       않음)까지 단언함으로써 "우연히 후보가 없어서 동일했다"가 아니라 <b>스위치가 실제로 게이트를
 *       비활성화했다</b>는 것을 고정한다.</li>
 *   <li>{@link #req022_missingCandidateRegressesToBaseline()} — {@code tripleCandidatesRoot}는
 *       지정하되(빈 디렉터리) 어떤 endpoint에도 promoted 후보가 없는 경우(NO_CANDIDATE, ablation
 *       스위치 없이도 자연 발생하는 미발화).</li>
 * </ol>
 *
 * <p>두 조건 모두 {@link FixtureBaselineE2E}와 동일한 부팅 계약(-Dsut.jar/-Dsut.src)을 재사용하고,
 * {@code --endpoint POST /api/transfers}로 탐색을 단일 endpoint로 좁혀(회귀 비교 대상과 실행 비용을
 * 모두 최소화) 세 빌드(기준/GRB_TRIAL=off/후보 부재)를 동일 조건으로 비교한다.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class TrialAblationE2E {

    private static final List<String> ONLY_TRANSFERS = List.of("POST /api/transfers");

    @TempDir
    Path baselineOut;

    @TempDir
    Path offOut;

    @TempDir
    Path noCandidateOut;

    @TempDir
    Path tripleRootWithCandidate;

    @TempDir
    Path emptyTripleRoot;

    @AfterEach
    void clearAblationSwitch() {
        // 다른 테스트로 새는 것을 막기 위해 항상 정리한다(REQ-022 스위치는 System property fallback).
        System.clearProperty("GRB_TRIAL");
    }

    @Test
    @DisplayName("REQ-022: GRB_TRIAL=off와 promoted 후보 부재 모두 게이트 미발화 기준과 정규화-동등")
    void req022_ablationAndNoCandidateRegressToBaseline() throws Exception {
        GraphAsset baselineGraph = buildOrderServiceGraph(baselineOut, null);
        EndpointExploration baselineEntry = transferEntry(readReport(baselineOut));
        // sanity: 비교 자체가 의미 있으려면 기준부터 outer-red(REQ-028)여야 한다 — 이미 2xx라면
        // 아래 ablation 빌드들의 "2xx 미도달"이 자명해져 게이트 미발화를 증명하지 못한다.
        assertOuterRedUnreached(baselineGraph, "기준(게이트 코드 진입 전)");

        seedStaleTripleCandidate(tripleRootWithCandidate);
        System.setProperty("GRB_TRIAL", "off");
        GraphAsset offGraph = buildOrderServiceGraph(offOut, tripleRootWithCandidate);
        System.clearProperty("GRB_TRIAL");
        EndpointExploration offEntry = transferEntry(readReport(offOut));

        assertNormalizedEquivalent("GRB_TRIAL=off", baselineGraph, baselineEntry, offGraph, offEntry);
        assertGateNeverInvoked("GRB_TRIAL=off", offEntry);
        // 핵심 단언: 게이트가 살아 있었다면 이 후보는 trial을 발화시켜 stale로 표면화됐을 것이다
        // (TriplePromotionE2E#req020 고정). off에서는 게이트 자체가 호출되지 않았어야 한다.
        assertOuterRedUnreached(offGraph, "GRB_TRIAL=off(STALE 유발 후보 존재)");

        GraphAsset noCandidateGraph = buildOrderServiceGraph(noCandidateOut, emptyTripleRoot);
        EndpointExploration noCandidateEntry = transferEntry(readReport(noCandidateOut));

        assertNormalizedEquivalent("후보 부재", baselineGraph, baselineEntry, noCandidateGraph, noCandidateEntry);
        assertGateNeverInvoked("후보 부재", noCandidateEntry);
        assertOuterRedUnreached(noCandidateGraph, "후보 부재");
    }

    // ─── 정규화 비교 ──────────────────────────────────────────────────────

    private void assertNormalizedEquivalent(String label, GraphAsset baselineGraph, EndpointExploration baselineEntry,
                                            GraphAsset actualGraph, EndpointExploration actualEntry) {
        GraphSetEquivDiffTool.DiffResult graphDiff = GraphSetEquivDiffTool.diff(baselineGraph, actualGraph);
        assertThat(graphDiff.equivalent())
                .as("%s: graph.json이 기준과 set-동등이어야 한다 — %s", label, GraphSetEquivDiffTool.report(graphDiff))
                .isTrue();

        assertThat(actualEntry.totalBranches()).as("%s: totalBranches", label).isEqualTo(baselineEntry.totalBranches());
        assertThat(actualEntry.coveredBranches()).as("%s: coveredBranches", label).isEqualTo(baselineEntry.coveredBranches());
        assertThat(actualEntry.solverRelevantMissed()).as("%s: solverRelevantMissed", label)
                .isEqualTo(baselineEntry.solverRelevantMissed());
        assertThat(actualEntry.noHappyPathReason()).as("%s: noHappyPathReason", label)
                .isEqualTo(baselineEntry.noHappyPathReason());
        assertThat(actualEntry.pathsByEngine()).as("%s: pathsByEngine", label).isEqualTo(baselineEntry.pathsByEngine());
        // 리스트 순서는 계약이 아니다(branch 분석기 순회 순서 비결정 가능) — set으로 비교.
        assertThat(new HashSet<>(actualEntry.missedBranches())).as("%s: missedBranches(set)", label)
                .isEqualTo(new HashSet<>(baselineEntry.missedBranches()));
        assertThat(new HashSet<>(actualEntry.droppedPaths())).as("%s: droppedPaths(set)", label)
                .isEqualTo(new HashSet<>(baselineEntry.droppedPaths()));
    }

    /** REQ-022 제외 키 목록(신규 REQ-021 관측 필드) — 게이트 미발화 시 항상 기본값이어야 한다. */
    private void assertGateNeverInvoked(String label, EndpointExploration entry) {
        assertThat(entry.trialCount()).as("%s: trialCount(게이트가 호출조차 되지 않았어야 함)", label).isZero();
        assertThat(entry.tripleAdopted()).as("%s: tripleAdopted", label).isFalse();
        assertThat(entry.tripleRejected()).as("%s: tripleRejected", label).isEmpty();
        assertThat(entry.staleTriples()).as("%s: staleTriples", label).isEmpty();
    }

    private void assertOuterRedUnreached(GraphAsset graph, String label) {
        List<ExploredPath> transferPaths = graph.paths().stream()
                .filter(p -> p.endpointId().equals("post-api-transfers")).toList();
        assertThat(transferPaths).as("%s: endpoint 자체가 회귀로 사라지지 않았어야 한다", label).isNotEmpty();
        assertThat(transferPaths).as("%s: 2xx SUCCESS 미도달(REQ-028 outer red)", label)
                .noneMatch(p -> p.expectedStatus() / 100 == 2 && p.outcome() == Outcome.Kind.SUCCESS);
    }

    // ─── fixture 헬퍼 ─────────────────────────────────────────────────────

    private ExplorationReport readReport(Path out) throws Exception {
        return Json.mapper().readValue(out.resolve("exploration-report.json").toFile(), ExplorationReport.class);
    }

    private EndpointExploration transferEntry(ExplorationReport report) {
        return report.endpoints().stream()
                .filter(e -> e.endpointId().equals("post-api-transfers"))
                .findFirst().orElseThrow(() -> new AssertionError("post-api-transfers entry missing from report"));
    }

    /**
     * {@link TriplePromotionE2E#seedStaleTripleCandidate()}와 동일한 고정(balance_amount(10) &lt;
     * amount(100) — 잔액 가드에서 422로 trial 실패) 후보를 배치한다. base/promoted를 완전히 동일하게
     * 둬 T1 마커-diff는 자명 통과시키고, 이 후보가 게이트 발화 시 STALE을 유발한다는 사실
     * (TriplePromotionE2E#req020 고정)만 이 테스트의 "GRB_TRIAL=off가 실제로 게이트를 막는지"
     * 검증에 재사용한다.
     */
    private void seedStaleTripleCandidate(Path tripleRoot) throws Exception {
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
     * {@link FixtureBaselineE2E#buildOrderServiceGraph()}와 동일한 부팅 계약 — {@code tripleCandidatesRoot}
     * (nullable)를 배선하고, 탐색을 {@code POST /api/transfers} 단일 endpoint로 좁힌다(세 빌드를 동일
     * 조건으로 비교하기 위함 — 실행 비용도 절감).
     */
    private GraphAsset buildOrderServiceGraph(Path out, Path tripleCandidatesRoot) throws Exception {
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
                null, io.graphrag.model.RequestHeaders.empty(), ONLY_TRANSFERS, "none", null, false, true,
                io.graphrag.builder.oracle.LlmOptions.disabled(), null, 1, null, null, 0, 0L, 0L,
                tripleCandidatesRoot));
    }
}
