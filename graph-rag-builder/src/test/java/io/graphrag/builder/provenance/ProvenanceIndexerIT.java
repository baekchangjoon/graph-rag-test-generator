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
 * REQ-002(+REQ-001 INPUT/EXTERNAL 부분)+REQ-003+REQ-004+REQ-032: 재귀 슬라이서 코어 — 호출그래프
 * DFS, depth cap/순환 종료, INPUT/DB_READ/EXTERNAL_RESPONSE/DERIVED 태깅, UNKNOWN+MULTI_IMPL
 * unresolved 표면화. 픽스처: src/test/resources/provenance-fixtures/
 * {basic,recursive,exists,jpa-override,external,derived,multiimpl}/.
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
    @DisplayName("REQ-004: findById를 재선언하지 않는 순정 JpaRepository도 DB_READ로 태깅(noClasspath 상속 메서드 회귀)")
    void req004_inheritedRepositoryMethodNotRedeclared() {
        // 실 SUT(order-service.TransferController/AccountRepository) 관례를 그대로 미러링: 리포지토리가
        // findById를 재선언하지 않으면 noClasspath에서 executable.getDeclaringType()/getType()(반환
        // 타입) 모두 해소되지 않는다 — 리시버(accountRepository) 정적 타입의 JpaRepository<Entity, Id>
        // 제네릭 인자로 엔티티 타입을 역산해도 DB_READ로 태깅되어야 한다.
        ProvenanceReport report = analyzeFixture(
                "jpa-inherited",
                "io.graphrag.fixture.jpainherited.JpaInheritedController",
                "create",
                3);

        assertThat(report.guards())
                .as("account.getBalance() 피연산자는 findById가 재선언되지 않아도 DB_READ + "
                        + "table=fund_accounts + column=balance_amount로 태깅되어야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.DB_READ
                        && "fund_accounts".equals(v.table()) && "balance_amount".equals(v.column())));
    }

    @Test
    @DisplayName("REQ-001: RestTemplate 래핑 클라이언트 응답의 accessor 체인이 EXTERNAL_RESPONSE로 태깅")
    void req001_externalResponseTagged() {
        // 실제 SUT(FraudClient/TransferController) 관례를 미러링: fraudClient.check(...)를 로컬
        // 변수(fraud)로 받고, record accessor(fraud.status())를 가드 조건에서 비교.
        ProvenanceReport report = analyzeFixture(
                "external",
                "io.graphrag.fixture.external.ExternalController",
                "create",
                3);

        assertThat(report.guards())
                .as("fraud.status() 피연산자는 EXTERNAL_RESPONSE + callSite(\"POST /fraud/check\") "
                        + "+ stubField(\"status\")로 태깅되어야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.EXTERNAL_RESPONSE
                        && "POST /fraud/check".equals(v.callSite())
                        && "status".equals(v.stubField())));
    }

    @Test
    @DisplayName("REQ-003: URL 인자가 리터럴이 아니면 callSite는 클라이언트클래스#메서드로 폴백")
    void req003_externalCallSiteFallsBackWhenUrlNotLiteral() {
        // DynamicUrlClient.check(path, ...)의 URL 인자는 메서드 파라미터(변수)라 path literal을
        // 추출할 수 없다 — bare 메서드명("postForObject")이 아니라 클라이언트클래스#메서드로
        // 폴백해야 추적성이 유지된다(리뷰 반영: 계약 "추출 가능한 범위까지, 불가하면 클래스#메서드").
        ProvenanceReport report = analyzeFixture(
                "external",
                "io.graphrag.fixture.external.DynamicUrlController",
                "create",
                3);

        assertThat(report.guards())
                .as("result.status() 피연산자는 EXTERNAL_RESPONSE이고 callSite는 "
                        + "\"io.graphrag.fixture.external.DynamicUrlClient#check\"로 폴백되어야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.EXTERNAL_RESPONSE
                        && "io.graphrag.fixture.external.DynamicUrlClient#check".equals(v.callSite())
                        && "status".equals(v.stubField())));
    }

    @Test
    @DisplayName("REQ-032: INPUT을 감싼 산술 파생식이 DERIVED로 태깅(concolic 해 배치는 C2 범위)")
    void req032_derivedTagged() {
        ProvenanceReport report = analyzeFixture(
                "derived",
                "io.graphrag.fixture.derived.DerivedController",
                "create",
                3);

        assertThat(report.guards())
                .as("req.getScore() * 2 전체가 하나의 리프로 DERIVED + javaType 유지로 태깅되어야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.DERIVED && v.javaType() != null));
    }

    @Test
    @DisplayName("REQ-003: 구현체 2개인 인터페이스 호출은 UNKNOWN + unresolved(MULTI_IMPL)")
    void req003_multiImplUnresolved() {
        ProvenanceReport report = analyzeFixture(
                "multiimpl",
                "io.graphrag.fixture.multiimpl.MultiImplController",
                "create",
                3);

        assertThat(report.unresolved())
                .as("PaymentGateway는 모델 내 구현체가 2개(Stripe/Paypal)이므로 unresolved에 "
                        + "MULTI_IMPL + targetType=PaymentGateway로 기록되어야 한다")
                .anyMatch(u -> u.reason() == Reason.MULTI_IMPL
                        && u.targetType().endsWith("PaymentGateway"));

        assertThat(report.guards())
                .as("gateway.charge(...) 피연산자는 origin=UNKNOWN으로 남아야 한다(literal이 아닌, "
                        + "즉 호출 자체가 미해결로 강등된 피연산자)")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.UNKNOWN && v.literal() == null && "String".equals(v.javaType())));
    }

    @Test
    @DisplayName("REQ-034: 중첩 DTO(List 원소 필드) 가드가 dot-path로 태깅")
    void req034_nestedDtoRecursion() {
        // fixture: if (req.items() == null || req.items().isEmpty() || req.items().get(0).qty() <= 0)
        ProvenanceReport report = analyzeFixture(
                "nested",
                "io.graphrag.fixture.nested.NestedController",
                "create",
                3);

        assertThat(report.guards())
                .as("req.items().get(0).qty() 피연산자는 대표원소 규약으로 INPUT + jsonPath=\"items.qty\"로 태깅되어야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.INPUT && "items.qty".equals(v.jsonPath())));
    }

    @Test
    @DisplayName("REQ-034: 중첩 DTO(Map 키 필드) 가드가 dot-path로 태깅")
    void req034_nestedDtoMapKeyRecursion() {
        // fixture: if (req.configs() == null || req.configs().get("region") == null)
        ProvenanceReport report = analyzeFixture(
                "nested",
                "io.graphrag.fixture.nested.NestedController",
                "createByConfig",
                3);

        assertThat(report.guards())
                .as("req.configs().get(\"region\") 피연산자는 Map 키 규약으로 INPUT + jsonPath=\"configs.region\"으로 태깅되어야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.INPUT && "configs.region".equals(v.jsonPath())));
    }

    @Test
    @DisplayName("REQ-034: List 인덱스가 0이 아니면 대표원소 규약으로 수렴하지 않고 UNKNOWN으로 남는다")
    void req034_nonZeroIndexNotTaggedAsRepresentativeElement() {
        // fixture: if (req.items().get(1).qty() <= 0) — downstream InputMutator.applyToBody가
        // 대표원소(arr.get(0))만 변이하므로, get(1)을 "items.qty"로 태깅하면 provenance와 실제
        // 변이 대상이 어긋난다. 그러므로 get(1) 피연산자는 UNKNOWN으로 강등되어야 한다.
        ProvenanceReport report = analyzeFixture(
                "nested",
                "io.graphrag.fixture.nested.NestedController",
                "createSecondItem",
                3);

        assertThat(report.guards())
                .as("req.items().get(1).qty() 피연산자는 \"items.qty\"로 태깅되면 안 된다")
                .allMatch(g -> g.operands().stream().noneMatch(v -> "items.qty".equals(v.jsonPath())));

        assertThat(report.guards())
                .as("req.items().get(1).qty() 피연산자는 UNKNOWN으로 남아야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.UNKNOWN && "int".equals(v.javaType())));
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
