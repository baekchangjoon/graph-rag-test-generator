package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.InvocationOutcome;
import io.graphrag.builder.oracle.ResponseClassifier;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import io.graphrag.model.RequiredSeed;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * T2 trial 실행기(REQ-013/014/016). 후보 1개를 실 SUT에 적용해 판정하고, 실패 시
 * {@link FailureDigest}를 산출한다. 시퀀스(REQ-013):
 * <ol>
 *   <li>① 기존 happy 시드 정리 — 현행 {@code EndpointExplorationRunner.resetSeeds}와 동일한
 *       reverse-DELETE 경로만 재사용한다(재삽입은 하지 않음 — candidate의 {@code seed.sql}이 그
 *       자리를 대체한다).</li>
 *   <li>② 후보 {@code seed.sql} INSERT — 삽입한 (table, pk 컬럼, pk 리터럴)을 추적해 finally에서
 *       역순 정리한다.</li>
 *   <li>③ 후보 {@code stubs.json} 등록 — 빈 객체({@code {}}, TripleSynthesizer의 "stub 없음" 관례)면
 *       skip. {@link #httpCapture}가 null(외부 stub 서버 미연결)이어도 skip.</li>
 *   <li>④ 후보 {@code body.json}으로 캡처-off invoke({@link TrialInvoker}, 실제로는
 *       {@code EndpointExplorationRunner.invokeTrial} 메서드 참조) 후
 *       {@link ResponseClassifier#classify}로 판정.</li>
 * </ol>
 *
 * <p>trial은 확정 run이 아니라 probe다 — 판정과 무관하게 이 후보가 삽입한 행은 finally에서 항상
 * 정리한다(다음 후보 시도가 DB 잔여 상태와 겹치지 않게). 승격(promoted/로 이동) 자체는 이 클래스의
 * 책임이 아니다 — {@link #runCandidate}는 판정 결과만 반환하고, 실제 {@link TripleStore#promote}/
 * {@link TripleStore#fail} 호출은 호출자(trial CLI 루프)가 예산 로직과 함께 수행한다.
 *
 * <p><b>attach 안전 게이트(REQ-023/024/025, Task 15):</b> {@code attachMode}가 true인 생성자로
 * 만들어지면(attach 여부는 호출자가 기존 환경 기술자 — {@code AttachedComposeEnvironment} 사용
 * 여부 — 로 판정해 넘긴다) 세 가지가 비-attach 경로와 달라진다:
 * <ol>
 *   <li>REQ-023 — {@code attachAllowSeedFlag}와 {@code confirmNonProductionFlag}가 <b>둘 다</b>
 *       true여야 후보 seed를 적용한다. 하나라도 false면 DB 쓰기를 전혀 시도하지 않고(happy 시드
 *       정리조차 skip) 사유가 담긴 실패 결과를 즉시 반환한다.</li>
 *   <li>REQ-024 — (seed가 실제로 적용된 경우) 종료 시 역-DELETE가 하나라도 실패하면 초기 invoke
 *       판정과 무관하게 그 후보의 승격을 차단하고, 잔존 (table, pk) 목록을 담은 digest를 반환한다.</li>
 *   <li>REQ-025 — {@code httpCapture}의 null 여부와 무관하게 stub 등록을 전혀 시도하지 않는다(attach
 *       WireMock 라우팅은 Phase C 소관) — 사유만 로그로 남긴다.</li>
 * </ol>
 * attachMode=false(기본 생성자)면 이 문단 전체가 no-op이고 기존 동작과 완전히 동일하다(회귀 0).
 */
public final class TrialRunner {

    private static final Logger log = LoggerFactory.getLogger(TrialRunner.class);

    /** REQ-015 캡처-off invoke 진입점. 운영 배선은 {@code EndpointExplorationRunner::invokeTrial}. */
    @FunctionalInterface
    public interface TrialInvoker {
        InvocationOutcome invoke(Endpoint endpoint, JsonNode body) throws Exception;
    }

    /**
     * 후보 1개의 trial 결과. {@code promoted=true}면 digest는 null.
     *
     * <p>{@code attachStubInapplicable}(REQ-025 코드리뷰 fix): attach 모드에서 후보의 {@code stubs.json}이
     * 비어있지 않았음에도(등록됐다면 실제로 쓰였을 EXTERNAL_RESPONSE 스텁) skip됐을 때만 true다 —
     * promoted/digest와 독립적으로 관측 가능해야 하므로(ADOPTED 경로에서도 true일 수 있다) 별도
     * 필드로 분리했다.
     */
    public record TrialOutcome(boolean promoted, int status, FailureDigest digest,
                               boolean attachStubInapplicable) {
    }

    /** {@link #registerCandidateStub}의 반환값 — stub id(nullable)와 REQ-025 관측 플래그. */
    private record StubRegistration(UUID stubId, boolean attachInapplicable) {
        private static final StubRegistration NONE = new StubRegistration(null, false);
    }

    /**
     * 실행 가능한 후보 seed INSERT 하나와 <b>그 행을 되돌리는 DELETE</b>의 완성된 계획(C4 리뷰
     * Critical 2/3 fix). {@code insertSql}/{@code deleteSql}의 테이블·컬럼 식별자는 후보 텍스트가
     * 아니라 <b>DB 카탈로그가 보고한 실제 식별자</b>이고 안전 식별자 정규식({@link #SAFE_IDENTIFIER})을
     * 통과한 것뿐이며, 값은 리터럴 문자열 결합이 아니라 {@link PreparedStatement} 파라미터로 바인딩된다.
     *
     * <p><b>N1 리뷰 Critical fix — {@code insertSql}은 후보 원문 줄이 아니라 재생성 SQL이다.</b>
     * 이전에는 후보 파일의 원문 줄을 {@code Statement.execute}로 그대로 실행했는데, MySQL/MariaDB의
     * 실행형 주석은 JSqlParser가 주석으로 버리는 반면 서버는 실행하므로 "파서가 본 것"과 "DB가
     * 실행하는 것"이 갈렸다 — T1 마커-diff·allowlist·정리 계획 어디에도 보이지 않는 행이 삽입되고,
     * 정리 DELETE는 추적된 PK만 지우므로 그 행이 영속 잔존했다. 지금은 {@link SeedSqlWhitelist}가
     * 검증하며 파싱한 {@link Insert}의 (컬럼, 닫힌 리터럴) 쌍에서 INSERT를 재생성하므로, 파서가 보지
     * 못한 텍스트는 구조적으로 DB에 도달할 수 없다.
     */
    private record SeedStatement(String insertSql, List<Object> insertValues, List<Integer> insertJdbcTypes,
                                 String deleteSql, List<Object> keyValues,
                                 List<Integer> keyJdbcTypes, String description) {
    }

    /** DB 카탈로그에서 읽은 한 테이블의 사실 — 실제 식별자 표기, PK 컬럼(KEY_SEQ 순), 컬럼→JDBC 타입. */
    private record TableFacts(String tableName, List<String> primaryKeyColumns,
                              Map<String, String> columnNames, Map<String, Integer> columnJdbcTypes) {
    }

    /** 정리 키를 스키마 사실로 결정할 수 없어 후보를 <b>DB 쓰기 전에</b> 차단할 때 던진다(fail-closed). */
    private static final class SeedPlanRejectedException extends Exception {
        private final transient List<String> reasons;

        private SeedPlanRejectedException(List<String> reasons) {
            super(String.join("; ", reasons));
            this.reasons = List.copyOf(reasons);
        }

        private List<String> reasons() {
            return reasons;
        }
    }

    /**
     * DELETE 문에 그대로 넣어도 안전한 식별자 형태. DB 카탈로그가 보고한 이름이라도 이 형태를 벗어나면
     * (예: 인용 식별자 안에 숨긴 {@code "; DROP TABLE ...; --}) 차단한다 — 식별자는 파라미터로 바인딩할
     * 수 없으므로, 화이트리스트 형태 + 방언별 인용을 함께 요구한다.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private final Connection connection;
    private final DbConfig.Type dbType;
    private final HttpCaptureServer httpCapture;   // nullable — null이면 stub 등록(③) skip
    private final ResponseClassifier classifier;
    private final SutHandle sut;                   // 로그 슬라이스(REQ-014 stackExcerpt)
    private final TrialInvoker invoker;
    private final boolean attachMode;                    // REQ-023/024/025 — attach 안전 게이트 활성 여부
    private final boolean attachAllowSeedFlag;            // --attach-allow-seed 존재 여부
    private final boolean confirmNonProductionFlag;       // --confirm-non-production 존재 여부
    /** lower(table) → 카탈로그 사실(빈 Optional = 미상/모호/안전하지 않음). 커넥션 단위로 1회만 조회. */
    private final Map<String, Optional<TableFacts>> tableFactsCache = new HashMap<>();

    public TrialRunner(Connection connection, DbConfig.Type dbType, HttpCaptureServer httpCapture,
                       ResponseClassifier classifier, SutHandle sut, TrialInvoker invoker) {
        this(connection, dbType, httpCapture, classifier, sut, invoker, false, false, false);
    }

    /**
     * REQ-023/024/025 attach 안전 게이트를 아는 생성자. {@code attachMode=false}면
     * {@code attachAllowSeedFlag}/{@code confirmNonProductionFlag}는 무시되고 기존(6-arg 생성자)
     * 동작과 완전히 동일하다.
     */
    public TrialRunner(Connection connection, DbConfig.Type dbType, HttpCaptureServer httpCapture,
                       ResponseClassifier classifier, SutHandle sut, TrialInvoker invoker,
                       boolean attachMode, boolean attachAllowSeedFlag, boolean confirmNonProductionFlag) {
        this.connection = connection;
        this.dbType = dbType;
        this.httpCapture = httpCapture;
        this.classifier = classifier;
        this.sut = sut;
        this.invoker = invoker;
        this.attachMode = attachMode;
        this.attachAllowSeedFlag = attachAllowSeedFlag;
        this.confirmNonProductionFlag = confirmNonProductionFlag;
    }

    public TrialOutcome runCandidate(Endpoint endpoint, Path candDir, List<RequiredSeed> happySeeds,
                                     ProvenanceReport report) throws Exception {
        // REQ-023: attach 이중 opt-in 미충족이면 happy 시드 정리조차 시도하지 않는다(DB 부작용 0) —
        // attach 환경은 실운영에 인접할 수 있어 "일단 정리하고 세부만 skip"조차 위험할 수 있다.
        if (attachMode && !attachSeedAllowed()) {
            String reason = attachSeedGateReason();
            log.warn("attach seed gate closed for {} (REQ-023, candidate skipped): {}", endpoint.id(), reason);
            return new TrialOutcome(false, -2, FailureDigest.forAttachSeedGateClosed(reason), false);
        }
        // C4 리뷰 Critical 2/3 fail-closed: 어떤 INSERT든 "되돌리는 DELETE"를 스키마 사실만으로 완성할
        // 수 없으면 DB를 전혀 건드리지 않고(happy 시드 정리 포함) 즉시 차단한다 — 잘못된 키로 나가는
        // DELETE는 attach 실 DB에서 조건에 맞는 모든 행을 지울 수 있으므로 "일단 넣고 본다"가 성립하지 않는다.
        List<SeedStatement> seedPlan;
        try {
            seedPlan = planCandidateSeed(candDir.resolve("seed.sql"));
        } catch (SeedPlanRejectedException e) {
            log.warn("candidate seed rejected before any DB write for {} (cleanup key unresolvable): {}",
                    endpoint.id(), e.reasons());
            return new TrialOutcome(false, -4, FailureDigest.forSeedCleanupUnresolvable(e.reasons()), false);
        }
        resetHappySeeds(happySeeds);
        // ②/③ 모두 try 안에서 수행한다 — 다중 INSERT 중 후반 statement가 던지거나 stubs.json이
        // malformed여도 finally가 반드시 실행돼야 한다(부분 삽입분 누수 방지). inserted는 try 밖에서
        // 선언해 finally에서 보이게 하고, insertCandidateSeed는 이 리스트에 "직접" append하므로
        // 도중에 던져도 그 시점까지 성공한 행은 리스트에 남아 정리 대상이 된다.
        List<SeedStatement> inserted = new ArrayList<>();
        UUID stubId = null;
        StubRegistration stubRegistration = StubRegistration.NONE;
        TrialOutcome outcome;
        // remainingRows는 try 밖에서 선언해, finally(예외 유무 무관 항상 실행)가 채운 값을 try-finally
        // 블록 이후에도 읽을 수 있게 한다 — try 안에서 예외가 던져지면 이 지점 이후 코드는 실행되지
        // 않고 그대로 전파되므로(REQ-013 기존 회귀와 동일), REQ-024 승격 차단은 "예외 없이 정상
        // 완료됐지만 cleanup만 실패한" 경우에만 적용된다.
        List<SeedStatement> remainingRows = new ArrayList<>();
        try {
            insertCandidateSeed(seedPlan, inserted);
            stubRegistration = registerCandidateStub(candDir.resolve("stubs.json"));
            stubId = stubRegistration.stubId();
            JsonNode body = Json.mapper().readTree(candDir.resolve("body.json").toFile());
            long logStart = sut.logOffset();
            InvocationOutcome invocation = invoker.invoke(endpoint, body);
            Outcome classified = classifier.classify(invocation.status(), invocation.response());
            if (classified.kind() == Outcome.Kind.SUCCESS) {
                outcome = new TrialOutcome(true, invocation.status(), null, stubRegistration.attachInapplicable());
            } else {
                long logEnd = sut.logOffset();
                String logExcerpt = sut.readLogRange(logStart, logEnd);
                String stackExcerpt = extractStackFrames(logExcerpt);
                FailureDigest digest = FailureDigest.of(invocation.status(), classified.kind(), body,
                        invocation.response(), logExcerpt, stackExcerpt, report);
                outcome = new TrialOutcome(false, invocation.status(), digest, stubRegistration.attachInapplicable());
            }
        } finally {
            if (stubId != null) {
                httpCapture.removeStub(stubId);
            }
            remainingRows.addAll(deleteInsertedRows(inserted));
        }
        // REQ-024: attach에서 역-DELETE가 하나라도 실패하면(전형적으로 invoke 중 SUT가 만든 FK 자식
        // 행) 원래 판정과 무관하게 승격을 차단하고 잔존 행을 보고한다.
        if (attachMode && !remainingRows.isEmpty()) {
            List<String> rowDescriptions = remainingRows.stream().map(SeedStatement::description).toList();
            log.warn("attach cleanup left row(s) behind for {} (REQ-024, promotion blocked): {}",
                    endpoint.id(), rowDescriptions);
            return new TrialOutcome(false, outcome.status(),
                    FailureDigest.forAttachCleanupBlocked(rowDescriptions), outcome.attachStubInapplicable());
        }
        return outcome;
    }

    /** REQ-023: 이중 opt-in(둘 다 true)이어야 attach에서 seed 적용을 허용한다. */
    private boolean attachSeedAllowed() {
        return attachAllowSeedFlag && confirmNonProductionFlag;
    }

    private String attachSeedGateReason() {
        List<String> missing = new ArrayList<>();
        if (!attachAllowSeedFlag) {
            missing.add("--attach-allow-seed");
        }
        if (!confirmNonProductionFlag) {
            missing.add("--confirm-non-production");
        }
        return "attach seed gate closed(REQ-023 이중 opt-in 미충족) — missing: " + String.join(", ", missing);
    }

    /** ① happy 시드 정리 — reverse-order(child→parent) DELETE만(재삽입 없음, resetSeeds와 구분). */
    private void resetHappySeeds(List<RequiredSeed> happySeeds) {
        if (happySeeds == null) {
            return;
        }
        for (int i = happySeeds.size() - 1; i >= 0; i--) {
            RequiredSeed seed = happySeeds.get(i);
            if (seed.columns().isEmpty() || seed.values().isEmpty()) {
                continue;
            }
            String sql = "DELETE FROM " + seed.table() + " WHERE " + seed.columns().get(0) + " = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, seed.values().get(0));
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("trial happy-seed reset delete failed: {}.{}", seed.table(), seed.columns().get(0), e);
            }
        }
    }

    /**
     * ②-pre 후보 {@code seed.sql}을 줄 단위로 파싱해 <b>실행 전에</b> "이 INSERT를 되돌리는 DELETE"까지
     * 완성한 계획을 만든다(C4 리뷰 Critical 2/3 fix). 한 줄이라도 계획을 완성할 수 없으면
     * {@link SeedPlanRejectedException}을 던져 <b>DB를 전혀 건드리지 않고</b> 후보를 차단한다(fail-closed).
     *
     * <p>계획 수립이 실패하는 조건(전부 "정리 키를 스키마 사실로 결정할 수 없음"):
     * <ul>
     *   <li>allowlist 밖 문장(파싱 실패·다중 문장·INSERT 아님·컬럼 목록 없음·비-리터럴 VALUES 등,
     *       {@link SeedSqlWhitelist#parseSingleInsert} 판정 그대로).</li>
     *   <li>스키마 한정 테이블명({@code schema.table}).</li>
     *   <li>DB 카탈로그에 그 이름의 테이블이 없거나 대소문자 무시 매칭이 <b>모호</b>(2개 이상).</li>
     *   <li>PK가 없는 테이블, 또는 PK 컬럼 중 하나라도 INSERT의 컬럼 목록에 없음.</li>
     *   <li>INSERT의 컬럼이 그 테이블의 실제 컬럼이 아님(스키마 대조 실패).</li>
     *   <li>테이블/컬럼 식별자가 {@link #SAFE_IDENTIFIER}를 벗어남(인용 식별자에 숨긴 문장 등).</li>
     *   <li>PK 위치의 값이 NULL이거나 닫힌 리터럴이 아님(키로 바인딩 불가).</li>
     * </ul>
     */
    private List<SeedStatement> planCandidateSeed(Path seedSqlFile) throws SeedPlanRejectedException {
        if (!Files.exists(seedSqlFile)) {
            return List.of();
        }
        String content;
        try {
            content = Files.readString(seedSqlFile);
        } catch (java.io.IOException e) {
            throw new SeedPlanRejectedException(List.of("seed.sql 읽기 실패: " + e));
        }
        List<SeedStatement> plan = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        SeedSqlWhitelist parser = new SeedSqlWhitelist();
        for (String line : SeedSqlWhitelist.nonBlankLines(content)) {
            List<String> parseReasons = new ArrayList<>();
            Optional<Insert> parsed = parser.parseSingleInsert(line, dbType, parseReasons);
            if (parsed.isEmpty()) {
                reasons.addAll(parseReasons);
                continue;
            }
            try {
                plan.add(planStatement(line, parsed.get()));
            } catch (SeedPlanRejectedException e) {
                reasons.addAll(e.reasons());
            } catch (SQLException e) {
                reasons.add("DB 카탈로그 조회 실패(" + line + "): " + e);
            }
        }
        if (!reasons.isEmpty()) {
            throw new SeedPlanRejectedException(reasons);
        }
        return plan;
    }

    private SeedStatement planStatement(String line, Insert insert)
            throws SeedPlanRejectedException, SQLException {
        if (insert.getTable().getSchemaName() != null) {
            throw new SeedPlanRejectedException(List.of(
                    "스키마 한정 테이블명은 정리 대상 해석 불가: " + insert.getTable().getFullyQualifiedName()));
        }
        String rawTable = insert.getTable().getUnquotedName();
        TableFacts facts = tableFacts(rawTable).orElseThrow(() -> new SeedPlanRejectedException(List.of(
                "DB 카탈로그에서 테이블을 유일하게 해석할 수 없거나 안전하지 않은 식별자: " + rawTable)));
        if (facts.primaryKeyColumns().isEmpty()) {
            throw new SeedPlanRejectedException(List.of(
                    "PK 미상 테이블은 정리(역-DELETE) 불가: " + facts.tableName()));
        }
        List<Column> columns = insert.getColumns();
        List<? extends Expression> values = insert.getValues().getExpressions();
        if (columns.size() != values.size()) {
            throw new SeedPlanRejectedException(List.of("INSERT 컬럼/값 개수 불일치: " + line));
        }
        Map<String, Expression> byColumn = new LinkedHashMap<>();
        // N1 fix: 실행할 INSERT를 파싱 결과에서 재생성한다 — 식별자는 카탈로그가 보고한(SAFE_IDENTIFIER를
        // 통과한) 이름으로 인용하고, 값은 전부 ? 파라미터로 바인딩한다. 후보 원문 텍스트는 한 글자도
        // 실행 SQL에 들어가지 않는다.
        List<String> insertColumns = new ArrayList<>();
        List<Object> insertValues = new ArrayList<>();
        List<Integer> insertJdbcTypes = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String rawColumn = columns.get(i).getUnquotedColumnName().toLowerCase(Locale.ROOT);
            if (!facts.columnNames().containsKey(rawColumn)) {
                throw new SeedPlanRejectedException(List.of("스키마에 없는 컬럼: "
                        + facts.tableName() + "." + columns.get(i).getUnquotedColumnName()));
            }
            Expression value = values.get(i);
            byColumn.put(rawColumn, value);
            insertColumns.add(quoteIdentifier(facts.columnNames().get(rawColumn)));
            if (value instanceof NullValue) {
                insertValues.add(null);   // NULL 리터럴 — setNull로 바인딩(키로는 쓰이지 않는다)
            } else {
                insertValues.add(closedLiteralValue(value).orElseThrow(() -> new SeedPlanRejectedException(
                        List.of("VALUES 위치 값이 바인딩 가능한 리터럴이 아님: "
                                + facts.tableName() + "." + rawColumn + " = " + value))));
            }
            insertJdbcTypes.add(facts.columnJdbcTypes().get(rawColumn));
        }
        String insertSql = "INSERT INTO " + quoteIdentifier(facts.tableName())
                + " (" + String.join(", ", insertColumns) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(insertColumns.size(), "?")) + ")";

        List<String> keyPredicates = new ArrayList<>();
        List<Object> keyValues = new ArrayList<>();
        List<Integer> keyJdbcTypes = new ArrayList<>();
        List<String> keyDescriptions = new ArrayList<>();
        for (String pkColumn : facts.primaryKeyColumns()) {
            String key = pkColumn.toLowerCase(Locale.ROOT);
            Expression expr = byColumn.get(key);
            if (expr == null) {
                throw new SeedPlanRejectedException(List.of("PK 컬럼이 INSERT 컬럼 목록에 없어 정리 불가: "
                        + facts.tableName() + "." + pkColumn));
            }
            Object value = closedLiteralValue(expr).orElseThrow(() -> new SeedPlanRejectedException(List.of(
                    "PK 위치 값이 바인딩 가능한 리터럴이 아님: " + facts.tableName() + "." + pkColumn + " = " + expr)));
            keyPredicates.add(quoteIdentifier(pkColumn) + " = ?");
            keyValues.add(value);
            keyJdbcTypes.add(facts.columnJdbcTypes().get(key));
            keyDescriptions.add(facts.tableName() + "." + pkColumn + "=" + value);
        }
        String deleteSql = "DELETE FROM " + quoteIdentifier(facts.tableName())
                + " WHERE " + String.join(" AND ", keyPredicates);
        return new SeedStatement(insertSql, insertValues, insertJdbcTypes,
                deleteSql, keyValues, keyJdbcTypes, String.join(", ", keyDescriptions));
    }

    /**
     * 후보 텍스트가 아니라 <b>DB 카탈로그</b>에서 테이블 사실을 읽는다(대소문자 무시 매칭, 결과가
     * 유일하지 않으면 미상 처리). 반환된 식별자는 전부 {@link #SAFE_IDENTIFIER}를 통과한 것뿐이다.
     */
    private Optional<TableFacts> tableFacts(String rawTableName) throws SQLException {
        String key = rawTableName.toLowerCase(Locale.ROOT);
        Optional<TableFacts> cached = tableFactsCache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<TableFacts> resolved = resolveTableFacts(rawTableName);
        tableFactsCache.put(key, resolved);
        return resolved;
    }

    private Optional<TableFacts> resolveTableFacts(String rawTableName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<String[]> matches = new ArrayList<>();   // {catalog, schema, name}
        // types=null로 조회한 뒤 직접 거른다 — 드라이버마다 기본 테이블 타입 표기가 다르다
        // ("TABLE" vs H2 2.x의 "BASE TABLE"). SYSTEM */VIEW는 seed 대상이 아니므로 제외한다.
        try (ResultSet rs = meta.getTables(null, null, "%", null)) {
            while (rs.next()) {
                String type = rs.getString("TABLE_TYPE");
                String name = rs.getString("TABLE_NAME");
                if (name == null || type == null) {
                    continue;
                }
                String upperType = type.toUpperCase(Locale.ROOT);
                if (!upperType.endsWith("TABLE") || upperType.startsWith("SYSTEM")) {
                    continue;
                }
                if (name.equalsIgnoreCase(rawTableName)) {
                    matches.add(new String[]{rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), name});
                }
            }
        }
        if (matches.size() != 1) {
            return Optional.empty();   // 미상 또는 동일명 다중 스키마(모호) — fail-closed
        }
        String catalog = matches.get(0)[0];
        String schema = matches.get(0)[1];
        String tableName = matches.get(0)[2];
        if (!SAFE_IDENTIFIER.matcher(tableName).matches()) {
            return Optional.empty();
        }

        Map<Short, String> pkBySeq = new java.util.TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                pkBySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        Map<String, String> columnNames = new LinkedHashMap<>();
        Map<String, Integer> columnJdbcTypes = new LinkedHashMap<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (column == null || !SAFE_IDENTIFIER.matcher(column).matches()) {
                    continue;   // 안전하지 않은 컬럼 식별자는 아예 알려진 컬럼으로 취급하지 않는다
                }
                columnNames.put(column.toLowerCase(Locale.ROOT), column);
                columnJdbcTypes.put(column.toLowerCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
            }
        }
        List<String> primaryKeyColumns = new ArrayList<>();
        for (String pk : pkBySeq.values()) {
            if (pk == null || !SAFE_IDENTIFIER.matcher(pk).matches()) {
                return Optional.empty();
            }
            primaryKeyColumns.add(pk);
        }
        return Optional.of(new TableFacts(tableName, primaryKeyColumns, columnNames, columnJdbcTypes));
    }

    /** 방언별 식별자 인용. 인자는 이미 {@link #SAFE_IDENTIFIER}를 통과한 이름이라 인용 문자가 없다. */
    private String quoteIdentifier(String identifier) {
        return dbType == DbConfig.Type.MYSQL || dbType == DbConfig.Type.MARIADB
                ? "`" + identifier + "`"
                : "\"" + identifier + "\"";
    }

    /**
     * 닫힌 리터럴 표현식을 바인딩 가능한 Java 값으로. NULL·비-리터럴은 빈 Optional(키로 쓸 수 없음).
     * 판정 집합은 {@link SeedSqlWhitelist}의 {@code isClosedLiteral}과 일치한다(Phase A 후속 Important 1) —
     * 단항 부호는 수치 리터럴({@code Long}/{@code Double})에만 붙일 수 있고, 부호 문자도 {@code +}/{@code -}만
     * 허용한다({@code ~} 등 다른 단항 연산자는 fail-closed로 거부).
     */
    private static Optional<Object> closedLiteralValue(Expression expr) {
        if (expr instanceof SignedExpression signed) {
            char sign = signed.getSign();
            if (sign != '+' && sign != '-') {
                return Optional.empty();
            }
            Object inner = closedLiteralValue(signed.getExpression()).orElse(null);
            if (inner instanceof Long value) {
                return Optional.of(sign == '-' ? -value : value);
            }
            if (inner instanceof Double value) {
                return Optional.of(sign == '-' ? -value : value);
            }
            return Optional.empty();
        }
        if (expr instanceof StringValue value) {
            return Optional.of(value.getValue());
        }
        if (expr instanceof LongValue value) {
            return Optional.of(value.getValue());
        }
        if (expr instanceof DoubleValue value) {
            return Optional.of(value.getValue());
        }
        if (expr instanceof BooleanValue value) {
            return Optional.of(value.getValue());
        }
        return Optional.empty();
    }

    /**
     * ② 계획된 후보 seed INSERT를 순서대로 실행하고, 성공한 문장을 {@code inserted}에 직접 append한다
     * (반환값이 아니라 파라미터 리스트를 채우는 이유 — Important 3 fix: 여러 INSERT 중 후반 문장이
     * 던지면 이 메서드 자체는 예외로 끝나지만, 이미 append된 앞쪽 성공 행은 호출자({@link #runCandidate})의
     * {@code inserted} 참조에 그대로 남아 finally의 {@link #deleteInsertedRows}가 부분 삽입분까지
     * 정리할 수 있다). INSERT 문장 자체는 allowlist({@link SeedSqlWhitelist}: 단일 INSERT + 닫힌
     * 리터럴 VALUES)를 통과한 파싱 결과에서 재생성한 SQL을 카탈로그 식별자 + PreparedStatement
     * 바인딩으로 실행한다 — 후보 파일의 원문 문자열은 실행하지 않는다(방언 실행형 주석 우회 차단).
     * 되돌리는 DELETE는 이미 {@link #planCandidateSeed}가 스키마 사실로 완성해 뒀다.
     *
     * <p><b>N1 리뷰 Critical fix:</b> 실행되는 것은 후보 파일의 <b>원문 줄이 아니라</b>
     * {@link #planStatement}가 파싱 결과(컬럼 목록 + 닫힌 리터럴)에서 재생성한 파라미터화 INSERT다.
     * 원문을 {@code Statement.execute}로 보내면 MySQL/MariaDB 실행형 주석처럼 "파서는 주석으로 버리고
     * 서버는 실행하는" 텍스트가 T1 검증·정리 계획을 통째로 우회한다 — 재생성 실행은 파서가 본 것과
     * DB가 실행하는 것이 같음을 구조적으로 보장한다.
     */
    private void insertCandidateSeed(List<SeedStatement> plan, List<SeedStatement> inserted) throws Exception {
        for (SeedStatement statement : plan) {
            // 실패 시(예: PK 충돌) 던져 이후 문장은 실행되지 않는다.
            try (PreparedStatement ps = connection.prepareStatement(statement.insertSql())) {
                bindParameters(ps, statement.insertValues(), statement.insertJdbcTypes());
                ps.executeUpdate();
            }
            inserted.add(statement);
        }
    }

    /**
     * 계획된 리터럴 값들을 {@link PreparedStatement} 파라미터로 바인딩한다. JDBC 타입을 카탈로그에서
     * 알아낸 컬럼은 타입을 명시하고(드라이버별 암묵 변환 편차 제거), 알 수 없으면 드라이버 추론에 맡긴다.
     */
    private static void bindParameters(PreparedStatement ps, List<Object> values, List<Integer> jdbcTypes)
            throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            Integer jdbcType = jdbcTypes.get(i);
            if (value == null) {
                ps.setNull(i + 1, jdbcType == null ? java.sql.Types.NULL : jdbcType);
            } else if (jdbcType == null) {
                ps.setObject(i + 1, value);
            } else {
                ps.setObject(i + 1, value, jdbcType);
            }
        }
    }

    /**
     * 후보가 삽입한 행을 역순으로 삭제한다(다음 trial 시도와 상태가 겹치지 않도록). 삭제 키는
     * {@link #planCandidateSeed}가 <b>DB 스키마의 PK 사실</b>로 결정해 둔 것이고 값은
     * {@link PreparedStatement} 파라미터로 바인딩된다 — 후보 텍스트가 DELETE의 SQL 구조에 영향을 주지
     * 않는다. best-effort — 개별 행 삭제 실패는 로그만 남기고 계속 진행하며, 실패한 행은 반환값에
     * 담아 호출자가 (attach 모드에서는 REQ-024 승격 차단으로) 판단할 수 있게 한다.
     */
    private List<SeedStatement> deleteInsertedRows(List<SeedStatement> rows) {
        List<SeedStatement> remaining = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            SeedStatement r = rows.get(i);
            try (PreparedStatement ps = connection.prepareStatement(r.deleteSql())) {
                bindParameters(ps, r.keyValues(), r.keyJdbcTypes());
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("trial candidate seed cleanup failed: {}", r.description(), e);
                remaining.add(r);
            }
        }
        return remaining;
    }

    /**
     * ③ 후보 {@code stubs.json}을 등록한다. httpCapture가 없거나(외부 stub 서버 미연결) 파일이 없거나
     * 빈 객체({@code {}}, TripleSynthesizer/{@code stubsJsonContent} "stub 없음" 관례)면 skip(null
     * 반환). 빈 객체 판정은 파싱된 {@link JsonNode#isEmpty()}로 한다 — {@code writerWithDefaultPrettyPrinter}가
     * 실제로는 {@code "{ }"}(공백 포함)를 산출하므로 원문 문자열 정확 일치({@code "{}"})는 오탐(실제
     * 빈 stub을 "내용 있음"으로 오판)을 낸다.
     *
     * <p>REQ-025: attach 모드에서는 {@code httpCapture}의 null 여부와 무관하게 이 메서드가 stub 등록을
     * 전혀 시도하지 않는다 — attach 환경이 실제로 쓰는 외부 stub WireMock으로의 라우팅은 Phase C
     * 소관이라, 여기서 임의로 candidate stub을 등록하면 그 attach 인스턴스의 실제 외부 의존성 응답을
     * 예측 불가하게 가로챌 위험이 있다. 코드리뷰 Important 2 fix: skip 사실이 로그 한 줄에 그치지
     * 않도록, "실제로 등록됐다면 쓰였을 non-empty stub이 있었는지"를 {@link StubRegistration#attachInapplicable()}로
     * 반환해 호출자가 {@link TrialOutcome#attachStubInapplicable()}을 거쳐 리포트/카운터까지 실어
     * 나를 수 있게 한다(빈 stub이라 원래도 등록 안 됐을 경우는 "inapplicable"이 아니므로 false).
     */
    private StubRegistration registerCandidateStub(Path stubsJsonFile) throws Exception {
        if (attachMode) {
            boolean hasContent = hasRegistrableStubContent(stubsJsonFile);
            if (hasContent) {
                log.info("attach mode: stub registration skipped for {} "
                        + "(REQ-025, attach WireMock routing is Phase C scope)", stubsJsonFile);
            }
            return new StubRegistration(null, hasContent);
        }
        if (httpCapture == null || !Files.exists(stubsJsonFile)) {
            return StubRegistration.NONE;
        }
        String content = Files.readString(stubsJsonFile);
        if (content.isBlank()) {
            return StubRegistration.NONE;
        }
        JsonNode node = Json.mapper().readTree(content);
        if (node == null || node.isEmpty()) {
            return StubRegistration.NONE;
        }
        StubMapping mapping = StubMapping.buildFrom(content);
        httpCapture.registerStub(mapping);
        return new StubRegistration(mapping.getId(), false);
    }

    /** stubs.json이 존재하고 공백이 아니며 파싱된 JSON이 비어있지 않은지("등록됐다면 실제 매핑이 됐을") 판정. */
    private static boolean hasRegistrableStubContent(Path stubsJsonFile) throws Exception {
        if (!Files.exists(stubsJsonFile)) {
            return false;
        }
        String content = Files.readString(stubsJsonFile);
        if (content.isBlank()) {
            return false;
        }
        JsonNode node = Json.mapper().readTree(content);
        return node != null && !node.isEmpty();
    }

    /** 로그 구간에서 {@code "at "}로 시작하는 스택 프레임 줄만 추출(REQ-014 stackExcerpt). 없으면 빈 문자열. */
    static String extractStackFrames(String logExcerpt) {
        if (logExcerpt == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : logExcerpt.split("\\R")) {
            if (line.strip().startsWith("at ")) {
                if (!sb.isEmpty()) {
                    sb.append(System.lineSeparator());
                }
                sb.append(line.strip());
            }
        }
        return sb.toString();
    }
}
