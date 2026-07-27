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
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private record InsertedRow(String table, String pkColumn, String pkLiteralSql) {
    }

    private final Connection connection;
    private final DbConfig.Type dbType;
    private final HttpCaptureServer httpCapture;   // nullable — null이면 stub 등록(③) skip
    private final ResponseClassifier classifier;
    private final SutHandle sut;                   // 로그 슬라이스(REQ-014 stackExcerpt)
    private final TrialInvoker invoker;
    private final boolean attachMode;                    // REQ-023/024/025 — attach 안전 게이트 활성 여부
    private final boolean attachAllowSeedFlag;            // --attach-allow-seed 존재 여부
    private final boolean confirmNonProductionFlag;       // --confirm-non-production 존재 여부

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
        resetHappySeeds(happySeeds);
        // ②/③ 모두 try 안에서 수행한다 — 다중 INSERT 중 후반 statement가 던지거나 stubs.json이
        // malformed여도 finally가 반드시 실행돼야 한다(부분 삽입분 누수 방지). inserted는 try 밖에서
        // 선언해 finally에서 보이게 하고, insertCandidateSeed는 이 리스트에 "직접" append하므로
        // 도중에 던져도 그 시점까지 성공한 행은 리스트에 남아 정리 대상이 된다.
        List<InsertedRow> inserted = new ArrayList<>();
        UUID stubId = null;
        StubRegistration stubRegistration = StubRegistration.NONE;
        TrialOutcome outcome;
        // remainingRows는 try 밖에서 선언해, finally(예외 유무 무관 항상 실행)가 채운 값을 try-finally
        // 블록 이후에도 읽을 수 있게 한다 — try 안에서 예외가 던져지면 이 지점 이후 코드는 실행되지
        // 않고 그대로 전파되므로(REQ-013 기존 회귀와 동일), REQ-024 승격 차단은 "예외 없이 정상
        // 완료됐지만 cleanup만 실패한" 경우에만 적용된다.
        List<InsertedRow> remainingRows = new ArrayList<>();
        try {
            insertCandidateSeed(candDir.resolve("seed.sql"), inserted);
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
            List<String> rowDescriptions = remainingRows.stream()
                    .map(r -> r.table() + "." + r.pkColumn() + "=" + r.pkLiteralSql())
                    .toList();
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
     * ② 후보 {@code seed.sql}을 줄 단위로 실행하고 삽입한 (table, pk컬럼, pk 리터럴)을 {@code inserted}에
     * 직접 append한다(반환값이 아니라 파라미터 리스트를 채우는 이유 — Important 3 fix: 여러 INSERT
     * 문장 중 후반 문장이 {@code st.execute}에서 던지면 이 메서드 자체는 예외로 끝나지만, 이미
     * append된 앞쪽 성공 행은 호출자({@link #runCandidate})의 {@code inserted} 참조에 그대로 남아
     * finally의 {@link #deleteInsertedRows}가 부분 삽입분까지 정리할 수 있다). 이 시점의 seed.sql은
     * 이미 T1 게이트({@link TripleValidator}, allowlist·마커-diff)를 통과했다는 전제이므로 파싱된
     * 원문을 그대로 실행한다.
     */
    private void insertCandidateSeed(Path seedSqlFile, List<InsertedRow> inserted) throws Exception {
        if (!Files.exists(seedSqlFile)) {
            return;
        }
        String content = Files.readString(seedSqlFile);
        SeedSqlWhitelist parser = new SeedSqlWhitelist();
        for (String line : SeedSqlWhitelist.nonBlankLines(content)) {
            List<String> ignoredReasons = new ArrayList<>();
            Optional<Insert> parsed = parser.parseSingleInsert(line, dbType, ignoredReasons);
            if (parsed.isEmpty()) {
                continue;   // 방어적 skip — T1 게이트를 통과한 후보 전제이므로 정상 경로에서는 발생하지 않음
            }
            try (Statement st = connection.createStatement()) {
                st.execute(line);   // 실패 시(예: PK 충돌) 여기서 던져 이 줄 이후는 실행되지 않는다.
            }
            Insert insert = parsed.get();
            String table = insert.getTable().getUnquotedName();
            List<Column> columns = insert.getColumns();
            if (columns != null && !columns.isEmpty() && insert.getValues() != null
                    && !insert.getValues().getExpressions().isEmpty()) {
                Expression firstValue = insert.getValues().getExpressions().get(0);
                inserted.add(new InsertedRow(table, columns.get(0).getUnquotedColumnName(), firstValue.toString()));
            }
        }
    }

    /**
     * 후보가 삽입한 행을 역순으로 삭제한다(다음 trial 시도와 상태가 겹치지 않도록). best-effort —
     * 개별 행 삭제 실패는 로그만 남기고 계속 진행한다. 삭제에 실패한 행은 반환값에 담아 호출자가
     * (attach 모드에서는 REQ-024 승격 차단으로) 판단할 수 있게 한다. 비-attach 경로는 이 반환값을
     * 사용하지 않으므로(기존과 동일하게 로그만으로 관측) 회귀가 없다.
     */
    private List<InsertedRow> deleteInsertedRows(List<InsertedRow> rows) {
        List<InsertedRow> remaining = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            InsertedRow r = rows.get(i);
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM " + r.table() + " WHERE " + r.pkColumn() + " = " + r.pkLiteralSql());
            } catch (Exception e) {
                log.warn("trial candidate seed cleanup failed: {}.{}={}",
                        r.table(), r.pkColumn(), r.pkLiteralSql(), e);
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
