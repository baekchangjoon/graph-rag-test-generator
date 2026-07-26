package io.graphrag.builder.provenance;

import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.Reason;
import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import spoon.reflect.CtModel;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-002(+REQ-001 INPUT 부분): 재귀 슬라이서 코어 — 호출그래프 DFS, depth cap/순환 종료,
 * INPUT 태깅. 픽스처: src/test/resources/provenance-fixtures/{basic,recursive,exists}/.
 */
class ProvenanceIndexerIT {

    private static final Path FIXTURES_ROOT = Path.of("src/test/resources/provenance-fixtures");

    @Test
    @DisplayName("REQ-002: 상호 재귀 소스에서 depth cap으로 종료, cap 초과는 UNKNOWN")
    void req002_recursionTerminates() {
        ProvenanceReport report = analyzeFixture(
                "recursive",
                "io.graphrag.fixture.recursive.RecursiveController",
                "run",
                3);

        assertThat(report.unresolved())
                .as("depth cap을 넘는 호출(step4)은 unresolved에 DEPTH_CAP으로 기록되어야 한다")
                .anyMatch(u -> u.reason() == Reason.DEPTH_CAP);
    }

    @Test
    void inputOperandTagged() {
        ProvenanceReport report = analyzeFixture(
                "basic",
                "io.graphrag.fixture.basic.BasicController",
                "create",
                3);

        assertThat(report.guards())
                .as("req.getAmount() < 1 가드의 좌변 피연산자는 INPUT + jsonPath=\"amount\"로 태깅되어야 한다")
                .anyMatch(g -> g.operands().stream()
                        .anyMatch(v -> v.origin() == Origin.INPUT && v.jsonPath().equals("amount")));
    }

    @Test
    @DisplayName("EXISTS 가드(Optional.orElseThrow) + record accessor INPUT 태깅")
    void existsGuardWithRecordAccessorTagged() {
        // record 기반 DTO(CreateTransferRequest.fromAccountId())를 쓰는 TransferController/
        // OrderController 실제 관례를 재현: accountRepository.findById(req.fromAccountId())
        // .orElseThrow(...) 가 EXISTS 가드로 수집되고, get/is 접두사 없는 record accessor의
        // 인자가 INPUT + jsonPath="fromAccountId"로 태깅되어야 한다.
        ProvenanceReport report = analyzeFixture(
                "exists",
                "io.graphrag.fixture.exists.ExistsController",
                "create",
                3);

        assertThat(report.guards())
                .as("orElseThrow EXISTS 가드가 record accessor의 INPUT 피연산자와 함께 수집되어야 한다")
                .anyMatch(g -> "EXISTS".equals(g.op())
                        && g.operands().stream().anyMatch(v -> v.origin() == Origin.INPUT
                                && "fromAccountId".equals(v.jsonPath())));
    }

    @Test
    @DisplayName("REQ-004: @Table/@Column 오버라이드가 ValueRef.table/column에 반영")
    void req004_jpaOverrides() {
        // fixture: @Table(name="fund_accounts") + @Column(name="balance_amount") long balance
        ProvenanceReport report = analyzeFixture(
                "jpa-override",
                "io.graphrag.fixture.jpaoverride.JpaOverrideController",
                "create",
                3);

        assertThat(report.guards())
                .as("repository에서 조회한 엔티티의 getter 체인은 DB_READ로 태깅되고, "
                        + "@Table/@Column 오버라이드가 table/column에 반영되어야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.DB_READ
                        && "fund_accounts".equals(v.table()) && "balance_amount".equals(v.column())));
    }

    @Test
    void recursionDoesNotHangOnMutualRecursion() {
        // methodA()↔methodB() 상호 재귀가 방문 집합으로 자연 종료하는지(무한루프 없이) 확인.
        // 테스트 자체가 유한 시간 내 반환되면 통과(타임아웃되면 실패).
        ProvenanceReport report = analyzeFixture(
                "recursive",
                "io.graphrag.fixture.recursive.RecursiveController",
                "run",
                3);

        assertThat(report).isNotNull();
    }

    private ProvenanceReport analyzeFixture(String fixtureName, String handlerClass,
                                            String handlerMethod, int maxDepth) {
        Path src = FIXTURES_ROOT.resolve(fixtureName);
        CtModel model = SharedSpoonModel.build(src);
        Endpoint endpoint = new Endpoint(
                "ep-" + fixtureName,
                "POST",
                "/api/" + fixtureName,
                handlerClass,
                handlerMethod,
                List.of(),
                false);
        return new ProvenanceIndexer().analyze(model, endpoint, maxDepth);
    }
}
