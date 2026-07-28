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
    @DisplayName("REQ-001: 가드에 참조되지 않는 필드는 unguarded + semanticHint로 태깅")
    void req001_unguardedFieldTagged() {
        // basic fixture: req.getAmount()만 가드에 쓰이고 req.getUserId()는 어디서도 참조되지 않는다
        // — userId는 unguarded + (String이고 email/phone/name/note류에 매칭되지 않으므로) "free-text".
        ProvenanceReport report = analyzeFixture(
                "basic",
                "io.graphrag.fixture.basic.BasicController",
                "create",
                3);

        assertThat(report.unguarded())
                .as("userId는 가드에 참조되지 않으므로 unguarded + javaType=String + semanticHint=free-text로 태깅되어야 한다")
                .anyMatch(u -> "userId".equals(u.jsonPath())
                        && "String".equals(u.javaType())
                        && "free-text".equals(u.semanticHint()));

        assertThat(report.unguarded())
                .as("amount는 가드에 참조되므로 unguarded에 나타나면 안 된다")
                .noneMatch(u -> "amount".equals(u.jsonPath()));
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
    @DisplayName("REQ-001: 커스텀 도메인 예외를 생성하는 orElseThrow도 EXISTS 가드로 수집")
    void req001_existsGuardWithCustomDomainException() {
        // tainted-spring 벤치마크 조사에서 드러난 최대 미인식 원인: 람다가 ResponseStatusException이
        // 아닌 프로젝트 고유 예외를 생성만 하면(표현식 람다 → CtThrow 없음) 가드가 통째로 누락됐다.
        ProvenanceReport report = analyzeFixture(
                "exists-custom",
                "io.graphrag.fixture.existscustom.CustomExistsController",
                "createWithCustomException",
                3);

        assertThat(report.guards())
                .as("커스텀 예외 orElseThrow가 EXISTS 가드로 수집되고 피연산자가 INPUT으로 태깅되어야 한다")
                .anyMatch(g -> "EXISTS".equals(g.op())
                        && g.operands().stream().anyMatch(v -> v.origin() == Origin.INPUT
                                && "fromAccountId".equals(v.jsonPath())));
    }

    @Test
    @DisplayName("REQ-001: 인자 없는 orElseThrow()도 EXISTS 가드로 수집")
    void req001_existsGuardWithNoArgOrElseThrow() {
        ProvenanceReport report = analyzeFixture(
                "exists-custom",
                "io.graphrag.fixture.existscustom.CustomExistsController",
                "createWithNoArgOrElseThrow",
                3);

        assertThat(report.guards())
                .as("무인자 orElseThrow()도 JDK가 NoSuchElementException을 던지므로 EXISTS 가드여야 한다")
                .anyMatch(g -> "EXISTS".equals(g.op())
                        && g.operands().stream().anyMatch(v -> v.origin() == Origin.INPUT
                                && "toAccountId".equals(v.jsonPath())));
    }

    @Test
    @DisplayName("REQ-001: 기본값 폴백(orElseGet)은 EXISTS 가드로 수집되면 안 된다")
    void req001_orElseGetFallbackIsNotAnExistsGuard() {
        // orElseGet은 값이 없어도 실행이 계속되므로 도달성을 막지 않는다.
        // 위 두 수정이 과잉 발동해 폴백까지 가드로 잡으면 불필요한 시드가 합성된다.
        ProvenanceReport report = analyzeFixture(
                "exists-custom",
                "io.graphrag.fixture.existscustom.CustomExistsController",
                "createWithFallback",
                3);

        assertThat(report.guards())
                .as("orElseGet 폴백은 가드가 아니다")
                .noneMatch(g -> "EXISTS".equals(g.op()));
    }

    @Test
    @DisplayName("REQ-004: EXISTS 가드는 조회 대상 테이블을 DB_READ 피연산자로 함께 싣는다(INPUT×DB_READ 교차 가드)")
    void req004_existsGuardCarriesReadTable() {
        // EXISTS 가드가 INPUT 피연산자만 실으면 TripleSynthesizer가 대상 테이블을 알 수 없어
        // seed 배치를 skip한다. 존재 가드의 의미는 "table에 이 PK를 가진 행이 있어야 한다"이므로
        // 수신 리포지토리 호출의 엔티티 테이블이 같은 가드 안에 있어야 합성이 성립한다.
        ProvenanceReport report = analyzeFixture(
                "jpa-inherited",
                "io.graphrag.fixture.jpainherited.JpaInheritedController",
                "create",
                3);

        assertThat(report.guards())
                .as("EXISTS 가드가 INPUT(accountId)과 DB_READ(fund_accounts)를 동시에 실어야 한다")
                .anyMatch(g -> "EXISTS".equals(g.op())
                        && g.operands().stream().anyMatch(v -> v.origin() == Origin.INPUT
                                && "accountId".equals(v.jsonPath()))
                        && g.operands().stream().anyMatch(v -> v.origin() == Origin.DB_READ
                                && "fund_accounts".equals(v.table())));
    }

    @Test
    @DisplayName("REQ-002: @PathVariable이 서비스 메서드 파라미터로 전파돼도 INPUT origin이 유지된다")
    void req002_pathVariablePropagatesThroughServiceParameter() {
        // 컨트롤러가 얇고 가드는 서비스에 있는 구조에서, 인자↔파라미터 바인딩이 없으면
        // 서비스 파라미터가 UNKNOWN이 되어 가드를 인식해도 시드/입력 채널로 라우팅할 수 없다.
        ProvenanceReport report = analyzeFixture(
                "param-propagation",
                "io.graphrag.fixture.paramprop.PropagationController",
                "get",
                3);

        assertThat(report.guards())
                .as("서비스 계층 EXISTS 가드의 피연산자가 핸들러 @PathVariable(jsonPath=\"id\")로 태깅돼야 한다")
                .anyMatch(g -> "EXISTS".equals(g.op())
                        && g.operands().stream().anyMatch(v -> v.origin() == Origin.INPUT
                                && "id".equals(v.jsonPath())));
    }

    @Test
    @DisplayName("REQ-002: @RequestBody 필드가 서비스 메서드 파라미터로 전파돼도 dot-path가 유지된다")
    void req002_requestBodyFieldPropagatesThroughServiceParameter() {
        ProvenanceReport report = analyzeFixture(
                "param-propagation",
                "io.graphrag.fixture.paramprop.PropagationController",
                "create",
                3);

        assertThat(report.guards())
                .as("서비스로 넘어간 body 필드는 원래 dot-path(ownerId)를 유지해야 한다")
                .anyMatch(g -> "EXISTS".equals(g.op())
                        && g.operands().stream().anyMatch(v -> v.origin() == Origin.INPUT
                                && "ownerId".equals(v.jsonPath())));
    }

    @Test
    @DisplayName("REQ-002: 서비스 계층 CtIf 가드에도 전파된 INPUT origin이 반영된다")
    void req002_propagationReachesServiceLayerIfGuard() {
        ProvenanceReport report = analyzeFixture(
                "param-propagation",
                "io.graphrag.fixture.paramprop.PropagationController",
                "limit",
                3);

        assertThat(report.guards())
                .as("서비스 CtIf 가드의 피연산자도 핸들러 입력(@RequestParam amount)으로 태깅돼야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v -> v.origin() == Origin.INPUT
                        && "amount".equals(v.jsonPath())));
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
    @DisplayName("REQ-032: INPUT을 감싼 산술 파생식이 DERIVED + derivedFrom(파생 루트 INPUT 필드)으로 태깅")
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

        assertThat(report.guards())
                .as("DERIVED 피연산자는 concolic 채널 위임 표시로 파생 루트 INPUT 필드 경로(score)를 "
                        + "derivedFrom에 담아야 한다 — 합성(C2)이 오라클 해를 배치할 body 필드의 근거")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.DERIVED && List.of("score").equals(v.derivedFrom())));
    }

    @Test
    @DisplayName("REQ-032: 다변수 파생식은 모든 파생 루트 INPUT 필드를 derivedFrom에 담는다(단락 없이 양변 분류)")
    void req032_multiRootDerivedCollectsEveryInputRoot() {
        ProvenanceReport report = analyzeFixture(
                "derived",
                "io.graphrag.fixture.derived.DerivedController",
                "createNonlinear",
                3);

        assertThat(report.guards())
                .as("req.getScore() * req.getFactor()는 좌변만 보고 멈추면 factor가 누락된다 — "
                        + "derivedFrom은 score와 factor를 모두 담아야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.DERIVED && v.derivedFrom() != null
                                && v.derivedFrom().containsAll(List.of("score", "factor"))));
    }

    @Test
    @DisplayName("REQ-001: DERIVED로 감싸인 INPUT 리프는 unguarded로 오탐되면 안 된다")
    void req001_derivedGuardFieldNotUnguarded() {
        // score는 유일한 가드(req.getScore() * 2 == 84)의 파생식 안에서만 쓰인다. derivesFromTrackedOrigin이
        // classifyOperand(req.getScore())를 호출해 INPUT임을 확인하고도 origin만 보고 jsonPath를 버리므로
        // (DERIVED ValueRef는 jsonPath=null), 이 리프가 실제로는 가드에 쓰였다는 사실이 별도로 적재되지
        // 않으면 unguarded로 오탐된다.
        ProvenanceReport report = analyzeFixture(
                "derived",
                "io.graphrag.fixture.derived.DerivedController",
                "create",
                3);

        assertThat(report.unguarded())
                .as("score는 DERIVED 가드(req.getScore() * 2 == 84)에 실제로 쓰였으므로 unguarded에 없어야 한다")
                .noneMatch(u -> "score".equals(u.jsonPath()));
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
    @DisplayName("REQ-005: List 타입 필드의 dot-path 접두사가 collectionPaths로 보고된다 "
            + "(대표원소 규약이 배열/객체 구분을 잃지 않게 하는 유일한 근거)")
    void req005_collectionPathsReportedForListFields() {
        // fixture: record CreateRequest(List<Item> items, Map<String,String> configs)
        ProvenanceReport report = analyzeFixture(
                "nested",
                "io.graphrag.fixture.nested.NestedController",
                "create",
                3);

        assertThat(report.collectionPaths())
                .as("items는 List<Item>이므로 배열 접두사로 보고되어야 한다 — 이 정보 없이 합성하면 "
                        + "{\"items\":{\"qty\":…}}처럼 배열이 객체가 되어 SUT가 400을 낸다")
                .contains("items");
        assertThat(report.collectionPaths())
                .as("Map은 동적 키라 대표원소 규약 대상이 아니므로 배열 접두사가 아니다")
                .doesNotContain("configs");
    }

    @Test
    @DisplayName("REQ-001: 컨테이너/스칼라 라이브러리 메서드(List.isEmpty 등)는 dot-path 세그먼트를 만들지 않는다 "
            + "— 실재하지 않는 필드(items.empty)를 INPUT으로 태깅하면 합성이 유령 body 필드를 만든다")
    void req001_containerApiCallsDoNotSynthesizePhantomDotPaths() {
        // fixture: if (req.items() == null || req.items().isEmpty() || req.items().get(0).qty() <= 0)
        ProvenanceReport report = analyzeFixture(
                "nested",
                "io.graphrag.fixture.nested.NestedController",
                "create",
                3);

        assertThat(report.guards())
                .as("List.isEmpty()는 JavaBean 접근자가 아니다 — \"items.empty\"라는 INPUT jsonPath가 "
                        + "만들어지면 안 된다(DTO에 그런 필드가 없다)")
                .allMatch(g -> g.operands().stream().noneMatch(v -> "items.empty".equals(v.jsonPath())));
        assertThat(report.guards())
                .as("그 자리는 UNKNOWN(boolean)으로 강등되어야 한다")
                .anyMatch(g -> g.operands().stream().anyMatch(v ->
                        v.origin() == Origin.UNKNOWN && "boolean".equals(v.javaType())));
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
